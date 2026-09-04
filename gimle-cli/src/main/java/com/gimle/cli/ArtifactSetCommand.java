package com.gimle.cli;

import com.gimle.core.hash.Sha256;
import com.gimle.core.module.ArtifactKind;
import com.gimle.core.module.ModuleArtifact;
import com.gimle.core.protocol.Json;
import com.gimle.core.vessel.VesselEntrypoint;
import com.gimle.module.artifact.ModuleArtifactReader;
import com.gimle.module.artifactset.ArtifactSetEntry;
import com.gimle.module.artifactset.ArtifactSetManifest;
import com.gimle.module.artifactset.ArtifactSetManifestParser;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * {@code kind: ArtifactSet} -- reached through {@link GimleCli#handleApply}'s kind-dispatch the
 * same way {@code Deployment}/{@code Job}/etc. are, never through a noun-verb pair of its own. A
 * manifest lists several artifacts to publish in one command; a plain module jar is resolved and
 * pushed the exact way a single {@code gimle artifact push} already would, a vessel jar is pushed
 * under the coordinate the entry itself names, and a bundle entry's directory is zipped -- with the
 * entry's own {@code command}/{@code workdir} written in as the {@code gimle-entrypoint.yaml}
 * launch descriptor at the archive root -- and pushed as a {@code BUNDLE}-kind artifact.
 *
 * <p>Publishing is deliberately not a transaction: a plain {@code HEAD} pre-flight check against
 * every coordinate first (touching nothing) catches any digest or kind conflict before a single
 * byte is pushed, then each member is pushed in the manifest's own order through the existing
 * single-artifact path. A mid-way failure leaves every already-pushed member valid and immutable --
 * nothing to roll back -- and re-applying the identical manifest resumes from the failure point,
 * since an already-pushed member simply comes back {@code IDENTICAL}. An entry that cannot be read
 * locally at all (a missing file, a jar with no module descriptor) follows the same rule rather
 * than a stricter one: it costs itself, the rest of the set publishes, and the command still fails
 * naming every entry it could not make sense of. A digest or kind <em>conflict</em> is the one
 * thing that still stops everything -- it means the manifest disagrees with what is already
 * published, which no amount of partial progress resolves. That resume property is what makes
 * deterministic bundle zipping (sorted entries, zeroed timestamps -- see {@link
 * #zipDirectoryDeterministically}) load-bearing rather than cosmetic: re-zipping an unchanged
 * directory must reproduce the identical digest, or a re-apply would spuriously conflict.
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

    Resolution resolution = resolveMembers(manifest);
    List<ResolvedMember> members = resolution.members();
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
    // Reported only now, with every publishable member already pushed. An entry naming a file that
    // isn't there is one entry's problem; refusing the whole manifest for it would hold back
    // members that are perfectly publishable and that a push-phase failure at the same position
    // would have published, since publishing here is explicitly not a transaction.
    if (!resolution.failures().isEmpty()) {
      throw CliException.invalidInput(
          members.size()
              + " of "
              + manifest.modules().size()
              + " pushed; not publishable: "
              + String.join("; ", resolution.failures()));
    }
  }

  /**
   * What {@link #resolveMembers} could and could not make sense of. Failures are collected rather
   * than thrown so one unreadable entry costs only itself.
   */
  private record Resolution(List<ResolvedMember> members, List<String> failures) {}

  /**
   * A manifest entry resolved to the coordinate and the exact file that will be uploaded -- for a
   * bundle, the deterministic zip is materialized here, exactly once, so the digest {@link
   * #preflight} checks and the bytes {@link #pushOne} uploads can never diverge.
   */
  private record ResolvedMember(
      Path uploadFile,
      String moduleId,
      String version,
      String sha256,
      Optional<String> tenantId,
      ArtifactKind kind) {}

  private Resolution resolveMembers(ArtifactSetManifest manifest) {
    List<ResolvedMember> members = new ArrayList<>();
    List<String> failures = new ArrayList<>();
    Map<String, Path> seenCoordinates = new LinkedHashMap<>();
    for (ArtifactSetEntry entry : manifest.modules()) {
      ResolvedMember member;
      try {
        member =
            switch (entry) {
              case ArtifactSetEntry.Module module -> resolveModule(module);
              case ArtifactSetEntry.Vessel vessel -> resolveVessel(vessel);
              case ArtifactSetEntry.Bundle bundle -> resolveBundle(bundle);
            };
      } catch (CliException e) {
        failures.add(entry.artifact() + ": " + e.getMessage());
        continue;
      }
      String coordinate = member.moduleId() + ":" + member.version();
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
      members.add(member);
    }
    return new Resolution(members, failures);
  }

  private static ResolvedMember resolveModule(ArtifactSetEntry.Module module) {
    ModuleArtifact artifact;
    try {
      artifact = ModuleArtifactReader.read(module.artifact());
    } catch (RuntimeException e) {
      throw new CliException(
          "not a pushable module artifact: "
              + module.artifact()
              + ": "
              + e.getMessage()
              + " -- a jar carrying no gimle-module.yaml is a kind: vessel entry, which names its"
              + " own coordinate instead of reading one",
          e);
    }
    return new ResolvedMember(
        module.artifact(),
        artifact.id().name(),
        artifact.id().version().toString(),
        artifact.sha256(),
        module.tenantId(),
        ArtifactKind.JAR);
  }

  private static ResolvedMember resolveVessel(ArtifactSetEntry.Vessel vessel) {
    if (!Files.isRegularFile(vessel.artifact())) {
      throw new CliException("vessel artifact not found: " + vessel.artifact());
    }
    String sha256;
    try {
      sha256 = Sha256.sha256Hex(vessel.artifact());
    } catch (IOException | UncheckedIOException e) {
      throw new CliException(
          "failed to read vessel artifact " + vessel.artifact() + ": " + e.getMessage(), e);
    }
    return new ResolvedMember(
        vessel.artifact(),
        vessel.name(),
        vessel.version(),
        sha256,
        vessel.tenantId(),
        ArtifactKind.JAR);
  }

  private ResolvedMember resolveBundle(ArtifactSetEntry.Bundle bundle) {
    if (!Files.isDirectory(bundle.artifact())) {
      throw new CliException(
          "bundle artifact must be a directory: "
              + bundle.artifact()
              + (Files.isRegularFile(bundle.artifact())
                  ? " (a single jar is kind: vessel, not kind: bundle)"
                  : ""));
    }
    try {
      Path zipFile =
          Files.createTempFile("gimle-bundle-" + bundle.name().replace('/', '_'), ".zip");
      zipFile.toFile().deleteOnExit();
      zipDirectoryDeterministically(bundle.artifact(), bundle.entrypoint(), zipFile);
      return new ResolvedMember(
          zipFile,
          bundle.name(),
          bundle.version(),
          Sha256.sha256Hex(zipFile),
          bundle.tenantId(),
          ArtifactKind.BUNDLE);
    } catch (IOException | UncheckedIOException e) {
      throw new CliException(
          "failed to zip bundle directory " + bundle.artifact() + ": " + e.getMessage(), e);
    }
  }

  /**
   * Zips {@code sourceDir}'s whole tree plus a generated {@code gimle-entrypoint.yaml} at the
   * archive root, deterministically: entries in sorted relative-path order (directory iteration
   * order is filesystem-dependent) with zeroed timestamps (a zip entry's mtime would otherwise
   * change the bytes on every run). The user's own directory is never touched -- the entrypoint
   * exists only inside the produced archive. A source directory already containing a file by the
   * reserved entrypoint name is rejected rather than silently overwritten either way.
   */
  static void zipDirectoryDeterministically(
      Path sourceDir, VesselEntrypoint entrypoint, Path zipFile) throws IOException {
    if (Files.exists(sourceDir.resolve(VesselEntrypoint.FILE_NAME))) {
      throw new CliException(
          sourceDir
              + " already contains a "
              + VesselEntrypoint.FILE_NAME
              + " -- the manifest entry's command/workdir generate that file; remove one or the"
              + " other");
    }
    List<Path> files;
    try (Stream<Path> tree = Files.walk(sourceDir)) {
      files = tree.filter(Files::isRegularFile).map(sourceDir::relativize).sorted().toList();
    }
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipFile))) {
      ZipEntry entrypointEntry = new ZipEntry(VesselEntrypoint.FILE_NAME);
      entrypointEntry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
      zip.putNextEntry(entrypointEntry);
      zip.write(entrypointYaml(entrypoint).getBytes(StandardCharsets.UTF_8));
      zip.closeEntry();
      for (Path relative : files) {
        // Zip entry names always use forward slashes, whatever the host filesystem separator.
        String entryName = relative.toString().replace('\\', '/');
        ZipEntry entry = new ZipEntry(entryName);
        entry.setTimeLocal(java.time.LocalDateTime.of(2000, 1, 1, 0, 0));
        zip.putNextEntry(entry);
        Files.copy(sourceDir.resolve(relative), zip);
        zip.closeEntry();
      }
    }
  }

  /**
   * Serializes the entrypoint as YAML with every scalar single-quoted (doubling embedded quotes,
   * YAML's own escape for them) -- safe for arbitrary argv strings without depending on a YAML
   * emitter library.
   */
  static String entrypointYaml(VesselEntrypoint entrypoint) {
    StringBuilder yaml = new StringBuilder("command:\n");
    for (String argument : entrypoint.command()) {
      yaml.append("  - ").append(quote(argument)).append('\n');
    }
    yaml.append("workdir: ").append(quote(entrypoint.workdir())).append('\n');
    return yaml.toString();
  }

  private static String quote(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  /**
   * A plain {@code HEAD} per coordinate, no writes -- a {@code Found} digest or kind that disagrees
   * with what this manifest would push aborts the whole set before anything is pushed, listing
   * every conflict. A member not yet present, or already present with matching bytes and kind, is
   * left for {@link #pushOne} to handle as the ordinary idempotent {@code PUT} it already is.
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
      ArtifactKind remoteKind = ArtifactKind.parse(head.kind().orElse(null));
      if (remoteKind != member.kind()) {
        conflicts.add(
            member.moduleId()
                + ":"
                + member.version()
                + " (already stored as kind "
                + remoteKind
                + ", this set declares "
                + member.kind()
                + ")");
      } else if (!remoteSha256.equals(member.sha256())) {
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
    Map<String, String> headers = new LinkedHashMap<>();
    member.tenantId().ifPresent(id -> headers.put("X-Gimle-Artifact-Tenant", id));
    if (member.kind() == ArtifactKind.BUNDLE) {
      headers.put("X-Gimle-Artifact-Kind", member.kind().name());
    }
    String path = "/artifacts/" + member.moduleId() + "/" + member.version();
    String response;
    try {
      response = client.expectSuccess(client.putFile(path, member.uploadFile(), headers));
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
    if (parsed.get("kind") != null) {
      row.put("artifactKind", parsed.get("kind"));
    }
    if (parsed.get("tenantId") != null) {
      row.put("tenantId", parsed.get("tenantId"));
    }
    return row;
  }
}
