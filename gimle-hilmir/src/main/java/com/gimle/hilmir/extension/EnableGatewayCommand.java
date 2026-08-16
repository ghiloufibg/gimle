package com.gimle.hilmir.extension;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.doctor.DoctorClusterCheck;
import com.gimle.hilmir.doctor.DoctorClusterCheck.RegistryOutcome;
import com.gimle.hilmir.release.ControlPlaneApi;
import com.gimle.hilmir.release.DeployCommand;
import com.gimle.hilmir.release.UpgradeCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * {@code hilmir enable gateway --server host:port [--modules-dir <dir>] [--values <file>] [--set
 * k=v]... [--wait] [--dry-run] [-o json]}: pushes {@code gimle-gateway}'s own jar to the artifact
 * registry (skipped if an identical-sha256 copy is already there) and deploys or upgrades a
 * synthesized {@code gimle-gateway} release built from it -- a thin, Helm-{@code
 * install}-equivalent wrapper entirely over the existing release-verb machinery (see {@link
 * GatewayBundleTemplate}, {@link DeployCommand}, {@link UpgradeCommand}); no new apply/ledger logic
 * of its own.
 *
 * <p>Deploy-vs-upgrade is decided by delegation, not a separate existence check: this always
 * attempts {@link UpgradeCommand#run} first (which already fails fast with a specific, stable "no
 * release named" message before any mutating call, even under {@code --dry-run}, if no {@code
 * gimle-gateway} release exists yet) and falls back to {@link DeployCommand#run} only on that exact
 * message -- the same decision {@code ReleaseLedger.readMeta} would make, without needing access to
 * that package-private class from outside {@code com.gimle.hilmir.release}.
 */
public final class EnableGatewayCommand {

  private static final Set<String> BOOLEAN_FLAGS = Set.of("--wait", "--dry-run");
  private static final Set<String> REPEATABLE_FLAGS = Set.of("--set");

  private static final String NO_EXISTING_RELEASE_MESSAGE =
      "no release named '"
          + GatewayBundleTemplate.RELEASE_NAME
          + "' -- use 'hilmir deploy' instead";

  private EnableGatewayCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ExtensionFlags flags = ExtensionFlags.parse(args, BOOLEAN_FLAGS, REPEATABLE_FLAGS);
    String serverAddress = ExtensionServer.resolve(flags);
    Path modulesDir = GatewayJarLocator.resolveModulesDir(flags);
    Path jarPath = GatewayJarLocator.findGatewayJar(modulesDir);
    GatewayJarLocator.Coordinate coordinate = GatewayJarLocator.deriveCoordinate(jarPath);

    try {
      return enable(flags, serverAddress, jarPath, coordinate, out);
    } catch (HilmirException e) {
      throw AuthErrors.translate(e);
    }
  }

  private static int enable(
      ExtensionFlags flags,
      String serverAddress,
      Path jarPath,
      GatewayJarLocator.Coordinate coordinate,
      PrintStream out) {
    boolean dryRun = flags.isSet("--dry-run");
    String coordinateLabel = coordinate.name() + "/" + coordinate.version();

    if (coordinatePresent(serverAddress, coordinate, jarPath)) {
      out.println(
          "artifact "
              + coordinateLabel
              + " already present in the registry (sha256 matches) --"
              + " skipping push");
    } else if (dryRun) {
      out.println("would push artifact " + coordinateLabel + " to the registry");
    } else {
      new ControlPlaneApi(serverAddress)
          .putFile("/artifacts/" + coordinate.name() + "/" + coordinate.version(), jarPath);
      out.println("pushed artifact " + coordinateLabel + " to the registry");
    }

    Path bundleFile = writeBundleFile(coordinate);
    List<String> delegateArgs = buildDelegateArgs(bundleFile, flags, serverAddress);
    try {
      return UpgradeCommand.run(delegateArgs, out);
    } catch (HilmirException e) {
      if (NO_EXISTING_RELEASE_MESSAGE.equals(e.getMessage())) {
        return DeployCommand.run(delegateArgs, out);
      }
      throw e;
    }
  }

  /**
   * {@code true} only when the registry already holds this exact coordinate with this exact jar's
   * own sha256 -- a differing sha256 (a stale jar left over under the same coordinate) or an
   * unreachable/unresolved registry both fall through to "push it", the safer default when presence
   * can't be positively confirmed.
   */
  private static boolean coordinatePresent(
      String serverAddress, GatewayJarLocator.Coordinate coordinate, Path jarPath) {
    DoctorClusterCheck check = new DoctorClusterCheck(serverAddress);
    RegistryOutcome outcome =
        check.checkRegistryCoordinate(coordinate.name(), coordinate.version());
    return outcome instanceof RegistryOutcome.Found found
        && found.sha256().equalsIgnoreCase(sha256Hex(jarPath));
  }

  private static Path writeBundleFile(GatewayJarLocator.Coordinate coordinate) {
    try {
      Path tempFile = Files.createTempFile("hilmir-gateway-bundle-", ".yaml");
      tempFile.toFile().deleteOnExit();
      Files.writeString(
          tempFile,
          GatewayBundleTemplate.render(coordinate.name(), coordinate.version()),
          StandardCharsets.UTF_8);
      return tempFile;
    } catch (IOException e) {
      throw new HilmirException(
          "failed writing the synthesized gateway bundle: " + e.getMessage(), e);
    }
  }

  private static List<String> buildDelegateArgs(
      Path bundleFile, ExtensionFlags flags, String serverAddress) {
    List<String> args = new ArrayList<>();
    args.add("-f");
    args.add(bundleFile.toString());
    args.add("--server");
    args.add(serverAddress);
    String valuesFile = flags.getOrDefault("--values", null);
    if (valuesFile != null) {
      args.add("--values");
      args.add(valuesFile);
    }
    for (String setFlag : flags.getAll("--set")) {
      args.add("--set");
      args.add(setFlag);
    }
    if (flags.isSet("--wait")) {
      args.add("--wait");
    }
    if (flags.isSet("--dry-run")) {
      args.add("--dry-run");
    }
    String outputFormat = flags.getOrDefault("-o", null);
    if (outputFormat != null) {
      args.add("-o");
      args.add(outputFormat);
    }
    return args;
  }

  private static String sha256Hex(Path file) {
    try (InputStream in = Files.newInputStream(file)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = in.read(buffer)) != -1) {
        digest.update(buffer, 0, read);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (IOException e) {
      throw new UncheckedIOException(
          "failed reading " + file + " to compute its digest: " + e.getMessage(), e);
    } catch (NoSuchAlgorithmException e) {
      throw new HilmirException("SHA-256 not available in this JVM", e);
    }
  }
}
