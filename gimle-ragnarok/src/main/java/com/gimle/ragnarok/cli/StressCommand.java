package com.gimle.ragnarok.cli;

import com.gimle.ragnarok.surtr.BundledModuleJarSource;
import com.gimle.ragnarok.surtr.ModuleJarSource;
import com.gimle.ragnarok.surtr.SingleJarModuleJarSource;
import com.gimle.ragnarok.surtr.SurtrReport;
import com.gimle.ragnarok.surtr.SurtrRunResult;
import com.gimle.ragnarok.surtr.SurtrRunner;
import com.gimle.ragnarok.surtr.SurtrWorkload;
import com.gimle.ragnarok.surtr.SurtrWorkloadParser;
import com.gimle.ragnarok.target.endpoint.EndpointClusterTarget;
import com.gimle.ragnarok.target.endpoint.TargetSpec;
import com.gimle.ragnarok.target.endpoint.TargetSpecParser;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code ragnarok stress --target <target.yaml> [--workload <name-or-path>] [--module-jar <path>]
 * [--report <dir>]} -- runs a {@link SurtrWorkload} against a real target. {@code --workload}
 * defaults to the bundled {@code pause-density} reference workload; {@code --module-jar} overrides
 * the default {@link BundledModuleJarSource} with an operator-supplied jar.
 */
public final class StressCommand {

  public static final String USAGE =
      "usage: ragnarok stress --target <target.yaml> [--workload <name-or-path>]\n"
          + "           [--module-jar <path>] [--report <dir>]";

  private static final String DEFAULT_WORKLOAD = "pause-density";

  private StressCommand() {}

  public static int run(final List<String> args, final PrintStream out) {
    final TargetSpec spec = TargetSpecParser.resolve(CliArgs.requireFlag(args, "--target"));
    final SurtrWorkload workload =
        SurtrWorkloadParser.resolve(
            CliArgs.optionalFlag(args, "--workload").orElse(DEFAULT_WORKLOAD));
    final ModuleJarSource moduleJarSource = resolveModuleJarSource(args);
    try (EndpointClusterTarget target = spec.open()) {
      final SurtrRunResult result = new SurtrRunner(target, workload, moduleJarSource).run();
      out.print(SurtrReport.render(result));
      CliArgs.optionalFlag(args, "--report")
          .ifPresent(dir -> out.println("wrote " + SurtrReport.write(result, Path.of(dir))));
      return result.passed() ? 0 : 1;
    }
  }

  private static ModuleJarSource resolveModuleJarSource(final List<String> args) {
    return CliArgs.optionalFlag(args, "--module-jar")
        .<ModuleJarSource>map(jar -> new SingleJarModuleJarSource(Path.of(jar)))
        .orElseGet(BundledModuleJarSource::new);
  }
}
