package com.gimle.hugin.model;

import com.gimle.cli.CliException;
import com.gimle.cli.spi.ClusterReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads {@code GET /health} and {@code GET /metrics} into one {@link PulseSnapshot}.
 *
 * <p>The health route answers {@code 503} when the control plane cannot reach its store, which the
 * client turns into a failure rather than a body -- so a control plane reporting itself down and
 * one that never answered arrive here the same way and have to be told apart by what did answer.
 * Both are reported as a state; neither is thrown, because a health screen that goes blank when
 * things go wrong is blank exactly when it is needed.
 */
public final class PulseReader {

  private final ClusterReader reader;

  public PulseReader(final ClusterReader reader) {
    this.reader = reader;
  }

  public PulseSnapshot read() {
    Map<String, Object> health;
    try {
      health = reader.getObject("/health");
    } catch (CliException e) {
      return PulseSnapshot.unreachable(reader.serverAddress(), reason(e));
    }
    return new PulseSnapshot(
        reader.serverAddress(),
        Optional.of(Instant.now()),
        stringOrDefault(health.get("status"), "UNKNOWN"),
        optionalString(health.get("reason")),
        number(health.get("uptimeSeconds")),
        stringOrDefault(health.get("transportProtocol"), "—"),
        (int) number(health.get("storeTenantCount")),
        traffic(),
        Optional.empty());
  }

  /**
   * The traffic rollup is best-effort on top of health: it is gated on a permission of its own, and
   * a caller who cannot read it should still see whether the control plane is up.
   */
  private List<PulseSnapshot.DeploymentTraffic> traffic() {
    List<Map<String, Object>> rows;
    try {
      rows = reader.getList("/metrics");
    } catch (CliException e) {
      return List.of();
    }
    List<PulseSnapshot.DeploymentTraffic> traffic = new ArrayList<>();
    for (Map<String, Object> row : rows) {
      String name = stringOrDefault(row.get("deploymentName"), "");
      if (name.isBlank()) {
        continue;
      }
      traffic.add(
          new PulseSnapshot.DeploymentTraffic(
              optionalString(row.get("tenantId")),
              name,
              (int) number(row.get("instanceCount")),
              decimal(row.get("avgRequestRatePerSecond")),
              decimal(row.get("avgErrorRatePerSecond"))));
    }
    return traffic;
  }

  /**
   * A {@code 503} carries the control plane's own reason in a body the client does not hand back,
   * so what is reported is the failure it did surface rather than an invented explanation.
   */
  private static String reason(final CliException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? "no answer" : message;
  }

  private static long number(final Object value) {
    return value instanceof Number n ? n.longValue() : 0L;
  }

  private static double decimal(final Object value) {
    return value instanceof Number n ? n.doubleValue() : 0.0;
  }

  private static String stringOrDefault(final Object value, final String fallback) {
    return value instanceof String s && !s.isBlank() ? s : fallback;
  }

  private static Optional<String> optionalString(final Object value) {
    return value instanceof String s && !s.isBlank() ? Optional.of(s) : Optional.empty();
  }
}
