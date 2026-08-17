package com.gimle.holmgang.rtm;

import com.gimle.holmgang.HolmgangException;
import com.gimle.holmgang.rtm.RtmCoverageChecker.CoverageResult;
import com.gimle.holmgang.rtm.RtmCoverageChecker.RtmDocument;
import com.gimle.holmgang.rtm.RtmCoverageChecker.RtmEntry;
import io.cucumber.plugin.ConcurrentEventListener;
import io.cucumber.plugin.event.EventPublisher;
import io.cucumber.plugin.event.TestRunFinished;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registered as a Cucumber plugin on the {@code HolmgangIT} suite (see its own {@code
 * PLUGIN_PROPERTY_NAME} configuration), this is the release-readiness gate: once every scenario in
 * the run has finished, it reads {@code RTM.md} and fails the whole suite if any implemented
 * requirement (i.e. not marked {@code Removed}) still has {@code Coverage: Not Covered} -- unless
 * explicitly whitelisted via {@link RtmCheckConfig#excludedIds()}. See {@link RtmCheckConfig} for
 * how to disable or configure it.
 *
 * <p>Deliberately hooks {@link TestRunFinished} rather than the JVM-shutdown-hook pattern {@code
 * SagaCollector} uses for its own end-of-run report: a shutdown hook fires too late in the JVM
 * lifecycle to fail the run through the JUnit Platform Suite engine that's actually executing it,
 * while throwing from a {@code TestRunFinished} handler propagates straight out through Cucumber's
 * own event bus and {@code Runtime.run()}, and is reported as a failure of the {@code HolmgangIT}
 * suite the ordinary way -- after every scenario has already run to completion, never interrupting
 * or skipping one.
 */
public final class RtmCoveragePlugin implements ConcurrentEventListener {

  private static final Logger log = LoggerFactory.getLogger(RtmCoveragePlugin.class);

  @Override
  public void setEventPublisher(final EventPublisher publisher) {
    publisher.registerHandlerFor(TestRunFinished.class, this::onRunFinished);
  }

  private void onRunFinished(final TestRunFinished event) {
    if (!RtmCheckConfig.enabled()) {
      log.info("RTM coverage gate disabled (-Dgimle.holmgang.rtmCheck.enabled=false); skipping.");
      return;
    }

    final RtmDocument document = RtmCoverageChecker.parse(RtmCheckConfig.rtmFile());
    final CoverageResult result = RtmCoverageChecker.check(document, RtmCheckConfig.excludedIds());

    if (!result.unknownExcludedIds().isEmpty()) {
      log.warn(
          "gimle.holmgang.rtmCheck.exclude names IDs not found among RTM.md's implemented"
              + " requirements (typo, or the requirement was renumbered/removed): {}",
          result.unknownExcludedIds());
    }

    if (result.passed()) {
      log.info(
          "RTM check passed: {}/{} requirements covered by Holmgang Cucumber tests.",
          result.totalChecked(),
          result.totalChecked());
      return;
    }

    final String message = formatFailure(document, result);
    log.error(message);
    throw new HolmgangException(message);
  }

  private static String formatFailure(final RtmDocument document, final CoverageResult result) {
    final StringBuilder sb = new StringBuilder(System.lineSeparator());
    sb.append("RTM check FAILED: ")
        .append(result.notCovered().size())
        .append(" of ")
        .append(result.totalChecked())
        .append(" requirements checked are not covered by a Holmgang Cucumber scenario.")
        .append(System.lineSeparator());
    for (final RtmEntry entry : result.notCovered()) {
      sb.append("  - ")
          .append(entry.id())
          .append(" -- ")
          .append(entry.feature())
          .append(" [")
          .append(document.categoryOf(entry.id()))
          .append(']')
          .append(System.lineSeparator());
    }
    sb.append(
            "Add a Cucumber scenario in Holmgang covering this requirement, or explicitly exclude"
                + " it (see below), before release.")
        .append(System.lineSeparator())
        .append(
            "  Whitelist a known/accepted gap:"
                + " -Dgimle.holmgang.rtmCheck.exclude=GIMLE-XXX,GIMLE-YYY")
        .append(System.lineSeparator())
        .append("  Disable this check entirely:    -Dgimle.holmgang.rtmCheck.enabled=false");
    return sb.toString();
  }
}
