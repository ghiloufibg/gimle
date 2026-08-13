package com.gimle.mimir.cron;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.TreeSet;

/**
 * A hand-rolled evaluator for the standard 5-field cron expression (minute hour day-of-month month
 * day-of-week) -- priority-3 design doc §3d's {@code CronJobSpec.schedule}. Deliberately not backed
 * by an external cron library, matching this codebase's existing posture of hand-rolling small,
 * self-contained parsers (see {@code DeploymentManifestParser}'s own javadoc) rather than adding a
 * dependency for one narrow need.
 *
 * <p>Supports the common syntax subset every real-world crontab actually uses: {@code *}, a single
 * number, a comma-separated list, a range ({@code a-b}), and a step ({@code * /n} or {@code a-b/n}
 * -- written with a space here only to avoid closing this comment early). Named months/days (
 * {@code JAN}, {@code MON}) are not supported -- numeric fields only.
 *
 * <p>All matching is in UTC, the same timezone every other reconciler in this codebase already
 * reasons in ({@code Clock.systemUTC()}) -- there is no per-tenant or per-cluster timezone
 * configuration anywhere in Gimlé, so introducing one just for cron scheduling would be new,
 * unrequested scope.
 *
 * <p>Day-of-month and day-of-week combine with cron's own well-understood (if surprising)
 * historical quirk: if <em>both</em> fields are restricted (neither is the literal {@code *}), a
 * moment matches if it satisfies <em>either</em> field, not both -- mirrored here deliberately
 * rather than invented, the same "well-understood reference semantic" posture the design doc's own
 * missed-schedule handling takes from Kubernetes CronJob.
 */
public final class CronSchedule {

  /**
   * Upper bound on how many minutes {@link #mostRecentDueInstant} will walk backward searching for
   * a match -- in steady-state operation (a reconciler ticking every few seconds) the gap between
   * {@code after} and {@code upTo} is always tiny, so this only matters after the control plane has
   * been down a long time or for an expression that fires very rarely (e.g. once a year); beyond
   * this bound, anything older is treated as not found rather than scanning indefinitely.
   */
  private static final int MAX_LOOKBACK_MINUTES = 60 * 24 * 7; // one week

  private final TreeSet<Integer> minutes;
  private final TreeSet<Integer> hours;
  private final TreeSet<Integer> daysOfMonth;
  private final TreeSet<Integer> months;
  private final TreeSet<Integer> daysOfWeek;
  private final boolean dayOfMonthRestricted;
  private final boolean dayOfWeekRestricted;
  private final String raw;

  private CronSchedule(
      TreeSet<Integer> minutes,
      TreeSet<Integer> hours,
      TreeSet<Integer> daysOfMonth,
      TreeSet<Integer> months,
      TreeSet<Integer> daysOfWeek,
      boolean dayOfMonthRestricted,
      boolean dayOfWeekRestricted,
      String raw) {
    this.minutes = minutes;
    this.hours = hours;
    this.daysOfMonth = daysOfMonth;
    this.months = months;
    this.daysOfWeek = daysOfWeek;
    this.dayOfMonthRestricted = dayOfMonthRestricted;
    this.dayOfWeekRestricted = dayOfWeekRestricted;
    this.raw = raw;
  }

  /**
   * @throws IllegalArgumentException if {@code expression} is not a valid 5-field expression.
   */
  public static CronSchedule parse(String expression) {
    if (expression == null) {
      throw new IllegalArgumentException("cron expression must not be null");
    }
    String[] fields = expression.trim().split("\\s+");
    if (fields.length != 5) {
      throw new IllegalArgumentException(
          "cron expression must have exactly 5 fields (minute hour dayOfMonth month dayOfWeek),"
              + " got "
              + fields.length
              + ": '"
              + expression
              + "'");
    }
    TreeSet<Integer> minutes = parseField(fields[0], 0, 59);
    TreeSet<Integer> hours = parseField(fields[1], 0, 23);
    TreeSet<Integer> daysOfMonth = parseField(fields[2], 1, 31);
    TreeSet<Integer> months = parseField(fields[3], 1, 12);
    TreeSet<Integer> daysOfWeek = parseField(fields[4], 0, 6);
    return new CronSchedule(
        minutes,
        hours,
        daysOfMonth,
        months,
        daysOfWeek,
        !fields[2].equals("*"),
        !fields[4].equals("*"),
        expression);
  }

  /**
   * The most recent minute-aligned instant in {@code (after, upTo]} that this schedule matches, or
   * empty if none exists in that window (including when {@code after} is not before {@code upTo},
   * or when nothing matches within {@link #MAX_LOOKBACK_MINUTES} of {@code upTo}). Walks backward
   * from {@code upTo} rather than forward from {@code after} so a frequently-firing schedule (the
   * overwhelmingly common case) returns almost immediately regardless of how far in the past {@code
   * after} is.
   */
  public Optional<Instant> mostRecentDueInstant(Instant after, Instant upTo) {
    if (!after.isBefore(upTo)) {
      return Optional.empty();
    }
    ZonedDateTime cursor = truncateToMinute(upTo);
    ZonedDateTime lowerBoundExclusive = truncateToMinute(after);
    // If `after` itself falls mid-minute, its own minute is still excluded -- truncateToMinute
    // rounds down, and the loop condition below (isAfter) already treats that minute as the floor.
    for (int steps = 0; steps <= MAX_LOOKBACK_MINUTES; steps++) {
      if (!cursor.toInstant().isAfter(lowerBoundExclusive.toInstant())) {
        return Optional.empty();
      }
      if (matches(cursor)) {
        return Optional.of(cursor.toInstant());
      }
      cursor = cursor.minusMinutes(1);
    }
    return Optional.empty();
  }

  private boolean matches(ZonedDateTime t) {
    if (!minutes.contains(t.getMinute())) {
      return false;
    }
    if (!hours.contains(t.getHour())) {
      return false;
    }
    if (!months.contains(t.getMonthValue())) {
      return false;
    }
    boolean domMatch = daysOfMonth.contains(t.getDayOfMonth());
    // java.time.DayOfWeek is MONDAY=1..SUNDAY=7; cron's own day-of-week field is
    // SUNDAY=0..SATURDAY=6.
    int dow = t.getDayOfWeek().getValue() % 7;
    boolean dowMatch = daysOfWeek.contains(dow);
    if (dayOfMonthRestricted && dayOfWeekRestricted) {
      return domMatch || dowMatch;
    }
    return domMatch && dowMatch;
  }

  private static ZonedDateTime truncateToMinute(Instant instant) {
    return instant.atZone(ZoneOffset.UTC).withSecond(0).withNano(0);
  }

  private static TreeSet<Integer> parseField(String field, int min, int max) {
    TreeSet<Integer> values = new TreeSet<>();
    for (String part : field.split(",")) {
      parsePart(part, min, max, values);
    }
    if (values.isEmpty()) {
      throw new IllegalArgumentException("cron field yielded no values: '" + field + "'");
    }
    return values;
  }

  private static void parsePart(String part, int min, int max, TreeSet<Integer> out) {
    if (part.isBlank()) {
      throw new IllegalArgumentException("cron field has a blank comma-separated entry");
    }
    String rangePart = part;
    int step = 1;
    int slash = part.indexOf('/');
    if (slash >= 0) {
      rangePart = part.substring(0, slash);
      step = parsePositiveInt(part.substring(slash + 1), part);
    }
    int rangeStart;
    int rangeEnd;
    if (rangePart.equals("*")) {
      rangeStart = min;
      rangeEnd = max;
    } else if (rangePart.contains("-")) {
      String[] bounds = rangePart.split("-", 2);
      rangeStart = parseFieldInt(bounds[0], min, max, part);
      rangeEnd = parseFieldInt(bounds[1], min, max, part);
      if (rangeStart > rangeEnd) {
        throw new IllegalArgumentException(
            "cron range start must not exceed its end: '" + part + "'");
      }
    } else {
      rangeStart = rangeEnd = parseFieldInt(rangePart, min, max, part);
    }
    for (int v = rangeStart; v <= rangeEnd; v += step) {
      out.add(v);
    }
  }

  private static int parseFieldInt(String text, int min, int max, String context) {
    int value = parsePositiveIntOrPlain(text, context);
    if (value < min || value > max) {
      throw new IllegalArgumentException(
          "cron field value "
              + value
              + " out of range ["
              + min
              + ","
              + max
              + "] in '"
              + context
              + "'");
    }
    return value;
  }

  private static int parsePositiveIntOrPlain(String text, String context) {
    try {
      return Integer.parseInt(text);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "invalid number '" + text + "' in cron field '" + context + "'", e);
    }
  }

  private static int parsePositiveInt(String text, String context) {
    int value = parsePositiveIntOrPlain(text, context);
    if (value <= 0) {
      throw new IllegalArgumentException("cron step must be positive in '" + context + "'");
    }
    return value;
  }

  @Override
  public String toString() {
    return raw;
  }
}
