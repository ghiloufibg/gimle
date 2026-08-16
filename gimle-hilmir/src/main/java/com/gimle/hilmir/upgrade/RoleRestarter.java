package com.gimle.hilmir.upgrade;

import com.gimle.hilmir.launch.RunRecord;
import com.gimle.hilmir.plan.ResolvedRuntime;
import com.gimle.hilmir.topology.ProcessRole;
import com.gimle.hilmir.topology.Topology;
import java.io.PrintStream;

/**
 * The one seam {@link UpgradeClusterCommand}'s orchestration loop calls through to actually restart
 * a role -- {@code MachineLauncher::restartRole} in production, matched exactly by this shape, and
 * a test double in {@code UpgradeClusterCommandTest} that can be made to fail on a chosen role
 * without spawning any real process, to prove the loop stops at the first failure rather than
 * continuing past it.
 */
@FunctionalInterface
interface RoleRestarter {

  RunRecord restart(
      Topology topology,
      String machine,
      ProcessRole role,
      ResolvedRuntime newRuntime,
      PrintStream out);
}
