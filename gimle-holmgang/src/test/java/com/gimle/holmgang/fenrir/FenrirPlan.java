package com.gimle.holmgang.fenrir;

import com.gimle.holmgang.HolmgangException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * An immutable, validated chaos plan: a seed, a soak window, an inter-fault gap range, the
 * deployments whose workers are fair game, and the weighted {@link Pool}s to draw faults from.
 * Built fluently from {@link #seeded(long)}; validated once at {@link #build()} and again against
 * the live cluster when {@link Fenrir#unleash} resolves each victim.
 *
 * <p>The seed makes a run's fault <em>sequence</em> and timing replayable: the same seed draws the
 * same gaps and pool choices in the same order. It is overridable at build time by the {@code
 * gimle.holmgang.chaosSeed} system property so a failing run can be re-run verbatim, and is always
 * printed into the ledger.
 */
public final class FenrirPlan {

  /** The system property that overrides a plan's seed, so any run can be replayed. */
  public static final String SEED_PROPERTY = "gimle.holmgang.chaosSeed";

  /**
   * A control-plane bounce that outlasts the node-dark timeout would mark healthy nodes dark and
   * trigger reschedule storms -- a compound fault masquerading as a single one. Dwells at or above
   * this bound are rejected so the bounce stays a clean single fault.
   */
  private static final Duration MAX_CONTROL_PLANE_DWELL = Duration.ofSeconds(10);

  private static final Duration DEFAULT_GATE_TIMEOUT = Duration.ofSeconds(60);

  private final long seed;
  private final Duration soak;
  private final Duration gapMin;
  private final Duration gapMax;
  private final List<String> eligibleDeployments;
  private final List<Pool> pools;
  private final boolean convergeBetweenFaults;
  private final Duration gateTimeout;

  private FenrirPlan(final Builder builder) {
    this.seed = builder.seed;
    this.soak = builder.soak;
    this.gapMin = builder.gapMin;
    this.gapMax = builder.gapMax;
    this.eligibleDeployments = List.copyOf(builder.eligibleDeployments);
    this.pools = List.copyOf(builder.pools);
    this.convergeBetweenFaults = builder.convergeBetweenFaults;
    this.gateTimeout = builder.gateTimeout;
  }

  public static Builder seeded(final long seed) {
    return new Builder(seed);
  }

  public long seed() {
    return seed;
  }

  public Duration soak() {
    return soak;
  }

  public Duration gapMin() {
    return gapMin;
  }

  public Duration gapMax() {
    return gapMax;
  }

  public List<String> eligibleDeployments() {
    return eligibleDeployments;
  }

  public List<Pool> pools() {
    return pools;
  }

  public boolean convergeBetweenFaults() {
    return convergeBetweenFaults;
  }

  public Duration gateTimeout() {
    return gateTimeout;
  }

  /** The fluent builder; every knob has a default except the pools and the soak window. */
  public static final class Builder {

    private long seed;
    private Duration soak;
    private Duration gapMin = Duration.ofSeconds(10);
    private Duration gapMax = Duration.ofSeconds(30);
    private final List<String> eligibleDeployments = new ArrayList<>();
    private final List<Pool> pools = new ArrayList<>();
    private boolean convergeBetweenFaults = true;
    private Duration gateTimeout = DEFAULT_GATE_TIMEOUT;

    private Builder(final long seed) {
      this.seed = seed;
    }

    public Builder soakFor(final Duration duration) {
      this.soak = duration;
      return this;
    }

    /** A fixed gap between strikes. */
    public Builder strikeEvery(final Duration gap) {
      this.gapMin = gap;
      this.gapMax = gap;
      return this;
    }

    /** A uniform-random gap between strikes, drawn per strike from the seed. */
    public Builder strikeEvery(final Duration min, final Duration max) {
      this.gapMin = min;
      this.gapMax = max;
      return this;
    }

    public Builder eligibleDeployments(final String... names) {
      for (final String name : names) {
        this.eligibleDeployments.add(name);
      }
      return this;
    }

    public Builder pool(final Pool pool) {
      this.pools.add(pool);
      return this;
    }

    /**
     * When {@code true} (the default) every next fault waits for full recovery from the last, so a
     * failure names one fault on a provably healthy cluster. When {@code false}, strikes fire on
     * the gap cadence regardless -- compound-fault mode, producing failures that need manual
     * attribution.
     */
    public Builder convergeBetweenFaults(final boolean converge) {
      this.convergeBetweenFaults = converge;
      return this;
    }

    public Builder gateTimeout(final Duration timeout) {
      this.gateTimeout = timeout;
      return this;
    }

    public FenrirPlan build() {
      this.seed = Long.getLong(SEED_PROPERTY, seed);
      validate();
      return new FenrirPlan(this);
    }

    private void validate() {
      if (soak == null || soak.isZero() || soak.isNegative()) {
        throw new HolmgangException("a plan must set a positive soak window via soakFor(...)");
      }
      if (gapMin.isNegative() || gapMin.isZero()) {
        throw new HolmgangException("the strike gap minimum must be positive");
      }
      if (gapMax.compareTo(gapMin) < 0) {
        throw new HolmgangException(
            "the strike gap maximum " + gapMax + " is below the minimum " + gapMin);
      }
      if (gateTimeout.isNegative() || gateTimeout.isZero()) {
        throw new HolmgangException("the recovery gate timeout must be positive");
      }
      if (pools.isEmpty()) {
        throw new HolmgangException("a plan must declare at least one pool");
      }
      final Set<FaultKind> kinds = new LinkedHashSet<>();
      for (final Pool pool : pools) {
        if (!kinds.add(pool.kind())) {
          throw new HolmgangException("duplicate pool for fault kind " + pool.kind());
        }
        if (pool.kind() == FaultKind.CONTROL_PLANE_BOUNCE
            && pool.dwell().compareTo(MAX_CONTROL_PLANE_DWELL) >= 0) {
          throw new HolmgangException(
              "control-plane bounce dwell "
                  + pool.dwell()
                  + " must stay under "
                  + MAX_CONTROL_PLANE_DWELL
                  + " to avoid tripping the node-dark timeout");
        }
      }
      if (kinds.contains(FaultKind.WORKER_KILL) && eligibleDeployments.isEmpty()) {
        throw new HolmgangException(
            "a worker-kill pool needs at least one eligible deployment to target");
      }
    }
  }
}
