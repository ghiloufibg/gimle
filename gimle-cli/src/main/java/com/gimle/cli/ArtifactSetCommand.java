package com.gimle.cli;

import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.protocol.Json;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.artifactset.ArtifactSetManifest;
import com.gimle.module.artifactset.ArtifactSetManifestParser;
import com.gimle.module.artifactset.ArtifactSetModuleEntry;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code kind: ArtifactSet} -- reached through {@link GimleCli#handleApply}'s kind-dispatch the
 * same way {@code Deployment}/{@code Job}/etc. are, never through a noun-verb pair of its own. A
 * manifest lists several module jars to publish in one command; each is resolved and pushed the
 * exact way a single {@code gimle artifact push} already would, just several times over.
 *
 * <p>Publishing is deliberately not a transaction: a plain {@code HEAD} pre-flight check against
 * every coordinate first (touching nothing) catches any digest conflict before a single byte is
 * pushed, then each member is pushed in the manifest's own order through the existing
 * single-artifact path. A mid-way failure leaves every already-pushed member valid and immutable --
 * nothing to roll back -- and re-applying the identical manifest resumes from the failure point,
 * since an already-pushed member simply comes back {@code IDENTICAL}.
 */
public final class ArtifactSetCommand {

  private final ControlPlaneClient client;
  private final OutputFormat.Kind output;
  private final PrintStream out;

  public ArtifactSetCommand(ControlPlaneClient client, OutputFormat.Kind output, PrintStream out) {
    this.client = client;
    this.output = output;
    this.out = out;
  }

  public void apply(List<String> args) {
    Path file = ManifestFiles.requireFileFlag(args);
    byte[] manifestBytes = ManifestFiles.readManifestBytes(file);
    ArtifactSetManifest manifest = ArtifactSetManifestParser.parse(file, manifestBytes);

    List<ResolvedMember> members = resolveMembers(manifest);
    preflight(members);

    List<Map<String, Object>> rows = new ArrayList<>();
    for (int i = 0; i < members.size(); i++) {
      try {
        rows.add(pushOne(members.get(i)));
      } catch (CliException e) {
        // Whatever landed before the failure is already valid and immutable -- print it rather
        // than swallow it, then fail loudly with exactly where the set stopped. Re-applying the
        // identical manifest resumes from here: every row already printed comes back IDENTICAL.
        OutputFormat.printList(output, rows, out);
        int remaining = members.size() - i - 1;
        throw new CliException(
            i
                + " of "
                + members.size()
                + " pushed, "
                + e.getMessage()
                + ", "
                + remaining
                + " not attempted",
            e);
      }
    }
    OutputFormat.printList(output, rows, out);
  }

  /** A manifest entry, resolved to the real coordinate its own jar declares. */
  private record ResolvedMember(
      Path jar, String moduleId, String version, String sha256, Optional<String> tenantId) {}

  private List<ResolvedMember> resolveMembers(ArtifactSetManifest manifest) {
    List<ResolvedMember> members = new ArrayList<>();
    Map<String, Path> seenCoordinates = new LinkedHashMap<>();
    for (ArtifactSetModuleEntry entry : manifest.modules()) {
      ModuleArtifact artifact;
      try {
        artifact = ModuleArtifactReader.read(entry.artifact());
      } catch (RuntimeException e) {
        throw new CliException(
            "not a pushable module artifact: " + entry.artifact() + ": " + e.getMessage(), e);
      }
      String moduleId = artifact.id().name();
      String version = artifact.id().version().toString();
      String coordinate = moduleId + ":" + version;
      Path previous = seenCoordinates.putIfAbsent(coordinate, entry.artifact());
      if (previous != null) {
        throw new CliException(
            "duplicate coordinate "
                + coordinate
                + " in this set: both "
                + previous
                + " and "
                + entry.artifact()
                + " resolve to it");
      }
      members.add(
          new ResolvedMember(
              entry.artifact(), moduleId, version, artifact.sha256(), entry.tenantId()));
    }
    return members;
  }

  /**
   * A plain {@code HEAD} per coordinate, no writes -- a {@code Found} digest that disagrees with
   * the local jar aborts the whole set before anything is pushed, listing every conflict. A member
   * not yet present, or already present with matching bytes, is left for {@link #pushOne} to handle
   * as the ordinary idempotent {@code PUT} it already is.
   */
  private void preflight(List<ResolvedMember> members) {
    List<String> conflicts = new ArrayList<>();
    for (ResolvedMember member : members) {
      ControlPlaneClient.HeadResult head =
          client.head("/artifacts/" + member.moduleId() + "/" + member.version());
      if (head.statusCode() == 404) {
        continue;
      }
      if (head.statusCode() != 200) {
        throw new CliException(
            "could not verify "
                + member.moduleId()
                + ":"
                + member.version()
                + " before pushing"
                + " (registry answered "
                + head.statusCode()
                + ")");
      }
      String remoteSha256 =
          head.sha256()
              .orElseThrow(
                  () ->
                      new CliException(
                          "registry sent no digest for an existing "
                              + member.moduleId()
                              + ":"
                              + member.version()));
      if (!remoteSha256.equals(member.sha256())) {
        conflicts.add(
            member.moduleId()
                + ":"
                + member.version()
                + " (local sha256 "
                + member.sha256()
                + ", registry sha256 "
                + remoteSha256
                + ")");
      }
    }
    if (!conflicts.isEmpty()) {
      throw new CliException(
          "pre-flight check failed, nothing was pushed -- conflicting coordinate(s): "
              + String.join(", ", conflicts));
    }
  }

  private Map<String, Object> pushOne(ResolvedMember member) {
    Map<String, String> headers =
        member.tenantId().map(id -> Map.of("X-Gimle-Artifact-Tenant", id)).orElse(Map.of());
    String path = "/artifacts/" + member.moduleId() + "/" + member.version();
    String response;
    try {
      response = client.expectSuccess(client.putFile(path, member.jar(), headers));
    } catch (CliException e) {
      throw new CliException(
          "failed at " + member.moduleId() + ":" + member.version() + ": " + e.getMessage(), e);
    }
    Map<String, Object> parsed = Json.asObject(Json.parse(response));
    boolean created = Boolean.TRUE.equals(parsed.get("created"));

    Map<String, Object> row = new LinkedHashMap<>();
    row.put("result", created ? "pushed" : "already-present");
    row.put("kind", "artifact");
    row.put("moduleId", member.moduleId());
    row.put("version", member.version());
    row.put("sha256", parsed.get("sha256"));
    if (parsed.get("tenantId") != null) {
      row.put("tenantId", parsed.get("tenantId"));
    }
    return row;
  }
}
