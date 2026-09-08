package com.gimle.hilmir.extension;

import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.release.ControlPlaneApi;
import com.gimle.hilmir.release.UndeployCommand;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code hilmir disable gateway --server host:port [-o json]}: undeploys the release named {@code
 * gimle-gateway} -- genuinely thin, since {@link UndeployCommand} is already generic over any
 * release name.
 *
 * <p>No {@code --keep-tenants} flag: {@link GatewayBundleTemplate}'s synthesized bundle declares no
 * {@code tenants:} entries of its own ({@code gimle-system} self-seeds at control-plane startup,
 * see {@code ApiServer.seedReservedSystemTenantIfAbsent}), so the release's own ledger meta never
 * records a tenant for {@code UndeployCommand} to delete in the first place -- offering a flag that
 * can never do anything for this specific release would be misleading rather than merely inert.
 */
public final class DisableGatewayCommand {

  private DisableGatewayCommand() {}

  public static int run(List<String> args, PrintStream out) {
    ExtensionFlags flags = ExtensionFlags.parse(args, Set.of(), Set.of());
    String serverAddress = ExtensionServer.resolve(flags);

    List<String> delegateArgs = new ArrayList<>();
    delegateArgs.add("--release");
    delegateArgs.add(GatewayBundleTemplate.RELEASE_NAME);
    delegateArgs.add("--server");
    delegateArgs.add(serverAddress);
    String outputFormat = flags.getOrDefault("-o", null);
    if (outputFormat != null) {
      delegateArgs.add("-o");
      delegateArgs.add(outputFormat);
    }

    try {
      return UndeployCommand.run(delegateArgs, out);
    } catch (HilmirException e) {
      throw AuthErrors.translate(translateNotEnabled(e, serverAddress));
    }
  }

  /**
   * "No release named" means only that <em>hilmir's own ledger</em> has no record of one -- true
   * both for a cluster that genuinely never ran {@code enable gateway}, and for a DaemonSet that
   * exists (deployed outside hilmir's own bookkeeping, or left behind by a ledger row that was
   * separately removed) with no ledger row to find. Reporting the first case's message for the
   * second leaves an operator who inherits a crashed, ledger-orphaned gateway with no working CLI
   * path to clean it up: they're told there is nothing to disable while the DaemonSet is plainly
   * still there. A direct existence check against the real workload -- the same coordinate {@link
   * GatewayBundleTemplate} always deploys under -- tells the two cases apart and points at the one
   * path that still works for the second.
   */
  private static HilmirException translateNotEnabled(HilmirException e, String serverAddress) {
    if (!("no release named '" + GatewayBundleTemplate.RELEASE_NAME + "'").equals(e.getMessage())) {
      return e;
    }
    if (new ControlPlaneApi(serverAddress)
        .exists("/daemonsets/" + GatewayBundleTemplate.WORKLOAD_NAME)) {
      return new HilmirException(
          "no hilmir-tracked gateway release found, but a DaemonSet named '"
              + GatewayBundleTemplate.WORKLOAD_NAME
              + "' still exists -- it was likely deployed or left behind outside hilmir's own"
              + " bookkeeping; remove it directly with 'gimle delete daemonset "
              + GatewayBundleTemplate.WORKLOAD_NAME
              + " --tenant "
              + GatewayBundleTemplate.TENANT_ID
              + "'",
          e);
    }
    return new HilmirException(
        "the gateway extension is not currently enabled on this cluster (nothing to disable)", e);
  }
}
