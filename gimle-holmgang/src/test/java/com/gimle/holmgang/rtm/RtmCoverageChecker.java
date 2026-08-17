package com.gimle.holmgang.rtm;

import com.gimle.holmgang.HolmgangException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parses {@code RTM.md}'s own machine-generated "## Summary Table" (one row per requirement: ID,
 * Feature, Status, Coverage) and checks it for implemented-but-uncovered requirements. Deliberately
 * reads only that table -- stable, one row per requirement -- rather than the free-text "Detailed
 * Requirements" prose below it, so a wording change in that prose can never silently break this
 * parser the way scraping narrative text would. The "## Coverage Gaps" table is read separately,
 * best-effort, purely to attach a Category to each not-covered requirement in the failure message;
 * its absence never fails the check, since it carries no pass/fail signal of its own -- the Summary
 * Table already does.
 */
public final class RtmCoverageChecker {

  /** One row of RTM.md's summary table. */
  public record RtmEntry(String id, String feature, String status, String coverage) {

    /** {@code Removed} requirements are no longer in the codebase and are never gated on. */
    public boolean isImplemented() {
      return !status.equalsIgnoreCase("Removed");
    }

    public boolean isCovered() {
      return coverage.equalsIgnoreCase("Covered");
    }
  }

  /**
   * RTM.md's summary-table entries plus the best-effort ID-to-Category lookup from its Coverage
   * Gaps table.
   */
  public record RtmDocument(List<RtmEntry> entries, Map<String, String> categoriesById) {

    public String categoryOf(final String id) {
      return categoriesById.getOrDefault(id, "(category unknown)");
    }
  }

  /** The outcome of checking a document's entries against an exclusion list. */
  public record CoverageResult(
      int totalChecked, List<RtmEntry> notCovered, List<String> unknownExcludedIds) {

    public boolean passed() {
      return notCovered.isEmpty();
    }
  }

  private static final String SUMMARY_HEADING = "## Summary Table";
  private static final String GAPS_HEADING = "## Coverage Gaps";
  private static final String ID_COLUMN = "id";
  private static final String FEATURE_COLUMN = "feature";
  private static final String STATUS_COLUMN = "status";
  private static final String COVERAGE_COLUMN = "coverage";
  private static final String CATEGORY_COLUMN = "category";

  private RtmCoverageChecker() {}

  /**
   * Reads and parses {@code rtmFile}. Fails loudly -- never returns a silently empty or partial
   * document -- whenever the file is missing/unreadable or its summary table isn't recognizable,
   * since a coverage gate that silently sees zero requirements is worse than no gate at all.
   */
  public static RtmDocument parse(final Path rtmFile) {
    if (!Files.isRegularFile(rtmFile)) {
      throw new HolmgangException(
          "RTM coverage gate enabled but RTM.md was not found at "
              + rtmFile.toAbsolutePath()
              + " -- generate it at the repo root, override its location with"
              + " -Dgimle.holmgang.rtmCheck.file=<path>, or disable the gate with"
              + " -Dgimle.holmgang.rtmCheck.enabled=false");
    }
    final List<String> lines;
    try {
      lines = Files.readAllLines(rtmFile);
    } catch (final IOException e) {
      throw new HolmgangException("failed reading RTM file " + rtmFile.toAbsolutePath(), e);
    }
    return parseLines(lines, rtmFile.toString());
  }

  /** Package-visible for direct unit testing without touching the filesystem. */
  static RtmDocument parseLines(final List<String> lines, final String sourceLabel) {
    final List<RtmEntry> entries = parseSummaryTable(lines, sourceLabel);
    final Map<String, String> categories = parseCoverageGapsCategoriesBestEffort(lines);
    return new RtmDocument(entries, categories);
  }

  private static List<RtmEntry> parseSummaryTable(
      final List<String> lines, final String sourceLabel) {
    final int summaryStart = indexOfHeading(lines, SUMMARY_HEADING);
    if (summaryStart < 0) {
      throw new HolmgangException(
          "RTM file "
              + sourceLabel
              + " has no '"
              + SUMMARY_HEADING
              + "' section -- its structure is unrecognizable; either RTM.md's own format changed,"
              + " or this isn't an RTM file at all");
    }

    Map<String, Integer> columns = null;
    int headerIndex = -1;
    for (int i = summaryStart + 1; i < lines.size(); i++) {
      final String line = lines.get(i).strip();
      if (line.startsWith("##")) {
        break; // ran into the next section without ever finding a table
      }
      if (line.startsWith("|")) {
        columns =
            parseHeaderRequiring(line, ID_COLUMN, FEATURE_COLUMN, STATUS_COLUMN, COVERAGE_COLUMN);
        if (columns != null) {
          headerIndex = i;
          break;
        }
      }
    }
    if (columns == null) {
      throw new HolmgangException(
          "RTM file "
              + sourceLabel
              + "'s '"
              + SUMMARY_HEADING
              + "' section has no recognizable ID|Feature|Status|Coverage table header -- its"
              + " structure is unrecognizable");
    }

    final List<RtmEntry> entries = new ArrayList<>();
    for (int i = headerIndex + 1; i < lines.size(); i++) {
      final String line = lines.get(i).strip();
      if (line.isEmpty() || line.startsWith("##")) {
        break;
      }
      if (!line.startsWith("|")) {
        break;
      }
      if (isSeparatorRow(line)) {
        continue;
      }
      entries.add(parseSummaryRow(line, columns, i + 1, sourceLabel));
    }

    if (entries.isEmpty()) {
      throw new HolmgangException(
          "RTM file "
              + sourceLabel
              + "'s summary table parsed to zero rows -- expected at least one requirement");
    }
    return entries;
  }

  private static RtmEntry parseSummaryRow(
      final String line,
      final Map<String, Integer> columns,
      final int lineNumber,
      final String sourceLabel) {
    final List<String> cells = splitRow(line);
    final int maxIndex =
        List.of(
                columns.get(ID_COLUMN),
                columns.get(FEATURE_COLUMN),
                columns.get(STATUS_COLUMN),
                columns.get(COVERAGE_COLUMN))
            .stream()
            .max(Integer::compareTo)
            .orElseThrow();
    if (cells.size() <= maxIndex) {
      throw new HolmgangException(
          "RTM file "
              + sourceLabel
              + " line "
              + lineNumber
              + " has fewer columns than the table header declared: "
              + line);
    }

    final String id = cells.get(columns.get(ID_COLUMN)).strip();
    final String feature = cells.get(columns.get(FEATURE_COLUMN)).strip();
    final String status = cells.get(columns.get(STATUS_COLUMN)).strip();
    final String coverage = cells.get(columns.get(COVERAGE_COLUMN)).strip();

    if (!id.matches("GIMLE-\\d+")) {
      throw new HolmgangException(
          "RTM file "
              + sourceLabel
              + " line "
              + lineNumber
              + " has an unrecognizable requirement ID (expected GIMLE-<number>): '"
              + id
              + "'");
    }
    if (!coverage.equalsIgnoreCase("Covered") && !coverage.equalsIgnoreCase("Not Covered")) {
      throw new HolmgangException(
          "RTM file "
              + sourceLabel
              + " line "
              + lineNumber
              + " ("
              + id
              + ") has an unrecognized Coverage value (expected 'Covered' or 'Not Covered'): '"
              + coverage
              + "'");
    }
    return new RtmEntry(id, feature, status, coverage);
  }

  /**
   * Best-effort ID-to-Category lookup from the "## Coverage Gaps" table. Never throws: a missing or
   * malformed Coverage Gaps section only means the failure message can't name a Category for a
   * given requirement, never that the gate itself can't run -- the Summary Table alone already
   * carries the pass/fail signal.
   */
  private static Map<String, String> parseCoverageGapsCategoriesBestEffort(
      final List<String> lines) {
    final int gapsStart = indexOfHeading(lines, GAPS_HEADING);
    if (gapsStart < 0) {
      return Map.of();
    }

    Map<String, Integer> columns = null;
    int headerIndex = -1;
    for (int i = gapsStart + 1; i < lines.size(); i++) {
      final String line = lines.get(i).strip();
      if (line.startsWith("|")) {
        columns = parseHeaderRequiring(line, ID_COLUMN, CATEGORY_COLUMN);
        if (columns != null) {
          headerIndex = i;
          break;
        }
      }
    }
    if (columns == null) {
      return Map.of();
    }

    final Map<String, String> categories = new LinkedHashMap<>();
    for (int i = headerIndex + 1; i < lines.size(); i++) {
      final String line = lines.get(i).strip();
      if (line.isEmpty() || !line.startsWith("|")) {
        break;
      }
      if (isSeparatorRow(line)) {
        continue;
      }
      final List<String> cells = splitRow(line);
      final int idIdx = columns.get(ID_COLUMN);
      final int catIdx = columns.get(CATEGORY_COLUMN);
      if (cells.size() <= Math.max(idIdx, catIdx)) {
        continue; // best-effort: skip a malformed row rather than failing the whole lookup
      }
      final String id = cells.get(idIdx).strip();
      if (id.matches("GIMLE-\\d+")) {
        categories.put(id, cells.get(catIdx).strip());
      }
    }
    return categories;
  }

  private static int indexOfHeading(final List<String> lines, final String heading) {
    for (int i = 0; i < lines.size(); i++) {
      if (lines.get(i).strip().equalsIgnoreCase(heading)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Returns a header line's column-name-to-index map (lower-cased names) if every column in {@code
   * required} is present, else {@code null} -- resilient to extra/reordered columns, since callers
   * only ever look up the columns they actually need.
   */
  private static Map<String, Integer> parseHeaderRequiring(
      final String headerLine, final String... required) {
    final List<String> cells = splitRow(headerLine);
    final Map<String, Integer> columns = new LinkedHashMap<>();
    for (int i = 0; i < cells.size(); i++) {
      columns.put(cells.get(i).toLowerCase(Locale.ROOT), i);
    }
    for (final String name : required) {
      if (!columns.containsKey(name)) {
        return null;
      }
    }
    return columns;
  }

  private static boolean isSeparatorRow(final String line) {
    return line.chars().allMatch(c -> c == '|' || c == '-' || c == ' ' || c == ':');
  }

  /**
   * Splits a markdown table row on unescaped {@code |}, dropping the empty leading/trailing cells
   * the row's own bounding pipes produce, and un-escaping {@code \|} back to a literal pipe.
   */
  private static List<String> splitRow(final String line) {
    final String[] raw = line.split("(?<!\\\\)\\|", -1);
    final List<String> cells = new ArrayList<>();
    for (int i = 1; i < raw.length - 1; i++) {
      cells.add(raw[i].strip().replace("\\|", "|"));
    }
    return cells;
  }

  /**
   * Filters {@code document}'s entries down to implemented (non-{@code Removed}) requirements not
   * in {@code excludedIds}, reporting which of those excluded IDs never matched an entry (a likely
   * typo or a since-renumbered/removed requirement).
   */
  public static CoverageResult check(final RtmDocument document, final Set<String> excludedIds) {
    final Set<String> normalizedExcluded =
        excludedIds.stream().map(id -> id.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
    final Set<String> matchedExcluded = new HashSet<>();

    final List<RtmEntry> notCovered = new ArrayList<>();
    int totalChecked = 0;
    for (final RtmEntry entry : document.entries()) {
      if (!entry.isImplemented()) {
        continue;
      }
      final String normalizedId = entry.id().toUpperCase(Locale.ROOT);
      if (normalizedExcluded.contains(normalizedId)) {
        matchedExcluded.add(normalizedId);
        continue;
      }
      totalChecked++;
      if (!entry.isCovered()) {
        notCovered.add(entry);
      }
    }

    final List<String> unknownExcludedIds =
        normalizedExcluded.stream()
            .filter(id -> !matchedExcluded.contains(id))
            .sorted()
            .collect(Collectors.toList());

    return new CoverageResult(totalChecked, List.copyOf(notCovered), unknownExcludedIds);
  }
}
