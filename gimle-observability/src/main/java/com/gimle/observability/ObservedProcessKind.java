package com.gimle.observability;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Which process kinds actually ship a given signal to Muninn, and therefore which ones a history
 * read can ever return anything for. One statement of that fact, so a picker offering a kind, a
 * command-line accepting one, and the API route serving it cannot drift into disagreeing about
 * which kinds exist -- each of those previously carried its own hand-maintained list.
 *
 * <p>A kind ships a signal here only if data of that signal genuinely reaches Muninn from it, not
 * merely if a shipper for it is wired: several processes install a trace exporter and then never
 * start a span, so their trace history is permanently empty, and offering it is as wrong as
 * refusing a kind that works. {@code MUNINN} itself is absent entirely (it is the sink, never a
 * shipper), and a hosted module such as the gateway ships under {@link #WORKER}, the worker JVM
 * that runs it, rather than a kind of its own.
 */
public enum ObservedProcessKind {

  /** The node agent ships its own request metrics; it deliberately installs no tracing at all. */
  AGENT(true, false),

  /**
   * The artifact registry ships its own request metrics. It installs a trace exporter too, but
   * nothing in it starts a span -- add {@code TRACES} here the day something does, or its history
   * is offered and always empty.
   */
  ANDVARI(true, false),

  /** Its {@code ApiServer} starts a server span per request it serves. */
  CONTROLPLANE(true, true),

  /** Metrics only, for the same reason as {@link #ANDVARI}. */
  FAFNIR(true, false),

  /**
   * Cluster DNS answers UDP queries and installs no tracer provider, so it has directory-staleness
   * metrics to ship and no spans to go with them.
   */
  SKALD(true, false),

  /**
   * The Raft state store, shipping under its own Raft id rather than an API address. Metrics only,
   * for the same reason as {@link #ANDVARI}.
   */
  STORE(true, false),

  /**
   * A worker JVM has no outbound network identity of its own: both signals reach Muninn relayed
   * byte-for-byte by the agent supervising it, under {@code {nodeId}:{workerId}}. Its spans are the
   * fabric's own, one per service call.
   */
  WORKER(true, true);

  /** The two signals a process kind can ship on its own behalf; logs are shipped per node. */
  public enum Signal {
    METRICS,
    TRACES
  }

  private final boolean shipsMetrics;
  private final boolean shipsTraces;

  ObservedProcessKind(boolean shipsMetrics, boolean shipsTraces) {
    this.shipsMetrics = shipsMetrics;
    this.shipsTraces = shipsTraces;
  }

  public boolean ships(Signal signal) {
    return switch (signal) {
      case METRICS -> shipsMetrics;
      case TRACES -> shipsTraces;
    };
  }

  /** Every kind shipping {@code signal}, in declaration (alphabetical) order. */
  public static List<String> namesShipping(Signal signal) {
    return Arrays.stream(values()).filter(kind -> kind.ships(signal)).map(Enum::name).toList();
  }

  /** Whether {@code name} (in any case) is a kind that ships {@code signal}. */
  public static boolean shipsSignal(String name, Signal signal) {
    return Arrays.stream(values())
        .anyMatch(kind -> kind.name().equals(name.toUpperCase(Locale.ROOT)) && kind.ships(signal));
  }
}
