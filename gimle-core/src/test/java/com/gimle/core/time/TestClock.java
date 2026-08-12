package com.gimle.core.time;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A manually-advanced {@link Clock} for testing time-dependent behavior without real waiting.
 *
 * <p>Deliberately a {@link Clock} subclass rather than a Gimlé-specific interface: production code
 * that wants to be testable this way takes a plain {@code java.time.Clock} and defaults it to
 * {@link Clock#systemUTC()}, so it carries no dependency on this class, on {@code gimle-core}'s
 * test-jar, or on any test framework -- the JDK's own abstraction is the whole seam.
 *
 * <p>Replaces the "shrink the production duration until the test is fast enough" pattern this repo
 * used everywhere before (a 20ms circuit-breaker cooldown raced against a 30ms {@code
 * Thread.sleep}, a 50ms node-dark timeout against a 60ms sleep, and so on). That pattern costs
 * twice: the margin between the configured duration and the sleep is the entire flakiness budget,
 * so a GC pause or a loaded CI box can invert them; and the value actually under test (20ms) is
 * nothing like the one production runs (5s), so the test never exercises the real configuration.
 * Advancing this clock is exact and instantaneous, which removes both problems at once -- a test
 * can assert against the genuine production duration and still cost no wall-clock time.
 *
 * <p>Thread-safe: production code under test routinely reads the clock from a thread other than the
 * one advancing it.
 *
 * <p><strong>Scope, honestly:</strong> this virtualizes only what a {@code Clock} controls -- "what
 * time is it now". It does <em>not</em> virtualize {@link Thread#sleep}, {@code
 * ScheduledExecutorService} tick scheduling, or {@code System.nanoTime()}. So it applies exactly
 * where the test itself drives execution -- calling {@code reconcileOnce()}, {@code allowRequest()}
 * and so on directly, with the clock deciding what those calls observe. It does not apply to a
 * component that paces itself from its own background ticker ({@code GossipMember}, {@code
 * ProbeLoop}, {@code MuninnShipper}): those would additionally need their scheduler injected and a
 * deterministic one supplied, which is a substantially larger change and not attempted here. Nor to
 * anything whose delay is real by nature -- a supervised subprocess actually sleeping between
 * restarts, a real socket timeout -- where wall-clock waiting is the thing under test.
 *
 * <p>One consequence worth knowing before reaching for this: virtual time only orders correctly
 * against timestamps that came from the same clock. Where a collaborator still stamps with the
 * system clock, use {@link #startingNow()} rather than the default start.
 */
public final class TestClock extends Clock {

  /**
   * A fixed, arbitrary, round starting point. Fixed rather than {@code Instant.now()} so a failure
   * reproduces identically on a re-run and any timestamp appearing in an assertion message is
   * stable across runs.
   */
  public static final Instant DEFAULT_START = Instant.parse("2026-01-01T00:00:00Z");

  private final AtomicReference<Instant> current;
  private final ZoneId zone;

  public TestClock() {
    this(DEFAULT_START);
  }

  public TestClock(Instant start) {
    this(new AtomicReference<>(start), ZoneOffset.UTC);
  }

  /**
   * A clock anchored at the real current instant instead of {@link #DEFAULT_START}, for the case
   * where the component under test compares its own {@code now} against a timestamp some
   * collaborator stamped with the system clock -- {@code StateStore#putNodeHeartbeat} records
   * {@code receivedAt = Instant.now()}, for instance, so a reconciler deciding whether that
   * heartbeat has gone stale is comparing across two clocks.
   *
   * <p>Virtual time only orders correctly against real timestamps when it starts from roughly the
   * same point: a clock left at {@link #DEFAULT_START} would read as years before a heartbeat
   * stamped seconds ago, making every staleness check nonsense. Anchoring here and then advancing
   * forward keeps the comparison meaningful while still costing no wall-clock time.
   *
   * <p>Prefer the plain constructor wherever the component reads every timestamp it compares from
   * the injected clock -- that case is fully virtual and needs no anchor.
   */
  public static TestClock startingNow() {
    return new TestClock(Instant.now());
  }

  private TestClock(AtomicReference<Instant> current, ZoneId zone) {
    this.current = current;
    this.zone = zone;
  }

  @Override
  public ZoneId getZone() {
    return zone;
  }

  /**
   * A zone-shifted view sharing this clock's own instant, per {@link Clock}'s contract -- advancing
   * either one moves both, rather than silently forking into a second timeline the test would then
   * have to keep in sync by hand.
   */
  @Override
  public Clock withZone(ZoneId newZone) {
    return new TestClock(current, newZone);
  }

  @Override
  public Instant instant() {
    return current.get();
  }

  /**
   * Moves time forward by {@code amount} and returns this clock, so an advance can be chained
   * inline with the call it is setting up.
   *
   * <p>Rejects a negative amount: every code path this clock exists to test treats time as moving
   * forward (a cooldown elapsing, a heartbeat going stale, a token expiring), so a negative advance
   * is a mistake in the test rather than a scenario worth expressing. A test that genuinely wants
   * to model a backwards wall-clock jump -- NTP correction, operator clock reset -- should say so
   * explicitly with {@link #setTo}.
   */
  public TestClock advance(Duration amount) {
    if (amount.isNegative()) {
      throw new IllegalArgumentException(
          "advance() moves time forward; use setTo() to model a deliberate backwards jump: "
              + amount);
    }
    current.updateAndGet(now -> now.plus(amount));
    return this;
  }

  /** Unguarded absolute set, including backwards -- see {@link #advance}'s own note on why. */
  public TestClock setTo(Instant target) {
    current.set(target);
    return this;
  }

  @Override
  public String toString() {
    return "TestClock[" + current.get() + " " + zone + "]";
  }
}
