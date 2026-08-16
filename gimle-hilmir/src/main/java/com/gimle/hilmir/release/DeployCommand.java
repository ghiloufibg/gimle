package com.gimle.hilmir.release;

import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code hilmir deploy -f <bundle.yaml> [--values <file>] [--set k=v]... [--wait] [--dry-run] [-o
 * json]}: renders the bundle, refuses if a release under this bundle's own {@code name} already
 * exists (the clear "use upgrade instead" error), applies its full state, and writes revision 1.
 *
 * <p>{@code --dry-run} renders and prints the plan without making any control-plane call at all --
 * not even the already-exists check, since there is nothing to check against without one.
 */
public final class DeployCommand {

  private static final Set<String> BOOLEAN_FLAGS = Set.of("--wait", "--dry-run");
  private static final Set<String> REPEATABLE_FLAGS = Set.of("--set");

  private DeployCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ReleaseFlags flags = ReleaseFlags.parse(args, BOOLEAN_FLAGS, REPEATABLE_FLAGS);
    boolean json = ReleaseOutput.isJson(flags);
    Path bundleFile = Path.of(flags.get("-f"));
    Bundle bundle = parseBundleFile(bundleFile);
    Map<String, String> values =
        ValueOverrides.merge(bundle.values(), valuesFileFlag(flags), flags.getAll("--set"));
    RenderedBundle rendered =
        BundleRenderer.render(bundle, values, bundleFile.toAbsolutePath().getParent());

    if (flags.isSet("--dry-run")) {
      ReleasePlan.print(rendered, json, out);
      return 0;
    }

    ControlPlaneApi api = new ControlPlaneApi(ReleaseServer.resolve(flags));
    if (ReleaseLedger.readMeta(api, rendered.name()).isPresent()) {
      throw new HilmirException(
          "release '" + rendered.name() + "' already exists -- use 'hilmir upgrade' instead");
    }

    ReleaseReconciler.DeployOutcome outcome =
        ReleaseReconciler.deployFresh(api, rendered, flags.isSet("--wait"), out);

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("result", "deployed");
    body.put("release", rendered.name());
    body.put("revision", outcome.revision());
    ReleaseOutput.printResult(
        json,
        body,
        "release/" + rendered.name() + " deployed (revision " + outcome.revision() + ")",
        out);
    return 0;
  }

  private static Optional<Path> valuesFileFlag(ReleaseFlags flags) {
    String value = flags.getOrDefault("--values", null);
    return value == null ? Optional.empty() : Optional.of(Path.of(value));
  }

  static Bundle parseBundleFile(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      return BundleParser.parse(in);
    } catch (IOException e) {
      throw new HilmirException("failed reading bundle file " + file + ": " + e.getMessage(), e);
    }
  }
}
