package com.gimle.hilmir.remote;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.RuntimeSettings;
import com.gimle.hilmir.topology.SshSettings;
import java.nio.file.Path;
import java.util.Optional;

/**
 * One machine's {@link SshSettings} with every field filled in -- the {@code --remote}-dispatch
 * counterpart of {@code com.gimle.hilmir.plan.ResolvedRuntime}, same shape and same reason:
 * resolution is a pure function of its inputs so the caller (a CLI entry point or a test) supplies
 * the actual defaults, keeping {@link RemoteDispatch} exactly as testable as {@code
 * com.gimle.hilmir.plan.LaunchPlanner} already is.
 *
 * <p>Precedence, highest first: the CLI's own {@link SshCliFlags} &gt; {@link Machine#ssh()} (a
 * per-machine override) &gt; {@link RuntimeSettings#ssh()} (a topology-wide default) &gt; this
 * class's own default ({@link #DEFAULT_INSTALL_DIR} for {@code installDir}; {@code user}/{@code
 * port}/{@code identityFile} are left unset so the operator's own {@code ssh} configuration --
 * agent, default key, {@code ~/.ssh/config} -- decides). {@code archive} has no CLI-flag tier or
 * class default of its own -- it's topology data (which local file to ship), not an operator
 * convenience, so it resolves to empty when unset at both tiers, and provisioning fails outright,
 * naming the machine, if it's ever needed while empty. {@code hostKeyFingerprint} has no tiering at
 * all: it identifies one specific host, so it comes straight off {@link Machine} itself.
 */
public record ResolvedSshTarget(
    String machineName,
    String host,
    Optional<String> user,
    Optional<Integer> port,
    Optional<String> identityFile,
    String installDir,
    Optional<Path> archive,
    Optional<String> hostKeyFingerprint) {

  public static final String DEFAULT_INSTALL_DIR = "/opt/gimle";

  public ResolvedSshTarget {
    if (machineName == null || machineName.isBlank()) {
      throw new GimleManifestException("resolved ssh target machineName must be non-blank");
    }
    if (host == null || host.isBlank()) {
      throw new GimleManifestException("resolved ssh target host must be non-blank");
    }
    if (user == null
        || port == null
        || identityFile == null
        || archive == null
        || hostKeyFingerprint == null) {
      throw new GimleManifestException("resolved ssh target fields must not be null");
    }
    if (installDir == null || installDir.isBlank()) {
      throw new GimleManifestException("resolved ssh target installDir must be non-blank");
    }
  }

  public static ResolvedSshTarget resolve(
      final Machine machine, final RuntimeSettings runtimeSettings, final SshCliFlags cliFlags) {
    final SshSettings machineSsh = machine.ssh().orElse(SshSettings.EMPTY);
    final SshSettings runtimeSsh = runtimeSettings.ssh().orElse(SshSettings.EMPTY);
    return new ResolvedSshTarget(
        machine.name(),
        machine.host(),
        firstPresent(cliFlags.user(), machineSsh.user(), runtimeSsh.user()),
        firstPresent(cliFlags.port(), machineSsh.port(), runtimeSsh.port()),
        firstPresent(cliFlags.identityFile(), machineSsh.identityFile(), runtimeSsh.identityFile()),
        firstPresent(cliFlags.installDir(), machineSsh.installDir(), runtimeSsh.installDir())
            .orElse(DEFAULT_INSTALL_DIR),
        firstPresent(machineSsh.archive(), runtimeSsh.archive()).map(Path::of),
        machine.sshHostKeyFingerprint());
  }

  /** The real {@code hilmir} binary this target's already-unpacked archive is assumed to have. */
  public String remoteHilmirBinary() {
    return installDir + "/bin/hilmir";
  }

  private static <T> Optional<T> firstPresent(
      final Optional<T> highest, final Optional<T> middle, final Optional<T> lowest) {
    if (highest.isPresent()) {
      return highest;
    }
    if (middle.isPresent()) {
      return middle;
    }
    return lowest;
  }

  private static <T> Optional<T> firstPresent(final Optional<T> highest, final Optional<T> lowest) {
    return highest.isPresent() ? highest : lowest;
  }
}
