package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SurefireReportsTest {

  @Test
  void sweep_finds_only_xml_directly_under_target_surefire_reports(@TempDir Path root)
      throws Exception {
    Path match = writeReport(root.resolve("mod-a"), "TEST-Example.xml", suiteXml(3, 0, 0, 0));
    Files.writeString(
        match.resolveSibling("Example.txt"), "not xml, must not match despite the directory");
    Path noTargetParent = root.resolve("mod-b/surefire-reports");
    Files.createDirectories(noTargetParent);
    Files.writeString(noTargetParent.resolve("TEST-Wrong.xml"), suiteXml(1, 0, 0, 0));
    Path wrongLeafDir = root.resolve("mod-c/target/other-reports");
    Files.createDirectories(wrongLeafDir);
    Files.writeString(wrongLeafDir.resolve("TEST-Wrong.xml"), suiteXml(1, 0, 0, 0));

    assertEquals(List.of(match), SurefireReports.sweep(root, null));
  }

  @Test
  void sweep_skips_reports_last_modified_before_the_cutoff(@TempDir Path root) throws Exception {
    Path stale = writeReport(root.resolve("mod-a"), "TEST-Stale.xml", suiteXml(1, 0, 0, 0));
    Instant cutoff = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    Files.setLastModifiedTime(stale, FileTime.from(cutoff.minus(1, ChronoUnit.HOURS)));
    Path fresh = writeReport(root.resolve("mod-b"), "TEST-Fresh.xml", suiteXml(1, 0, 0, 0));
    Files.setLastModifiedTime(fresh, FileTime.from(cutoff.plusSeconds(5)));

    assertEquals(List.of(fresh), SurefireReports.sweep(root, cutoff));
    assertEquals(List.of(stale, fresh), SurefireReports.sweep(root, null));
  }

  @Test
  void totals_sum_across_report_files(@TempDir Path root) throws Exception {
    writeReport(root.resolve("mod-a"), "TEST-A.xml", suiteXml(5, 1, 0, 2));
    writeReport(root.resolve("mod-b"), "TEST-B.xml", suiteXml(3, 0, 1, 0));

    SurefireReports.Totals totals =
        SurefireReports.totals(SurefireReports.sweep(root, null), new SystemStreamLog());
    assertEquals(new SurefireReports.Totals(8, 1, 1, 2, 0), totals);
  }

  @Test
  void a_testsuites_wrapper_element_is_summed_over_its_children(@TempDir Path root)
      throws Exception {
    writeReport(
        root.resolve("mod-a"),
        "TEST-Aggregate.xml",
        "<testsuites>" + suiteXml(2, 1, 0, 0) + suiteXml(4, 0, 0, 1) + "</testsuites>");

    SurefireReports.Totals totals =
        SurefireReports.totals(SurefireReports.sweep(root, null), new SystemStreamLog());
    assertEquals(new SurefireReports.Totals(6, 1, 0, 1, 0), totals);
  }

  @Test
  void a_malformed_report_is_skipped_not_fatal(@TempDir Path root) throws Exception {
    writeReport(root.resolve("mod-a"), "TEST-Good.xml", suiteXml(2, 0, 0, 0));
    writeReport(root.resolve("mod-b"), "TEST-Bad.xml", "<testsuite tests=\"9\" unclosed");

    List<Path> reports = SurefireReports.sweep(root, null);
    assertEquals(2, reports.size());
    assertEquals(
        new SurefireReports.Totals(2, 0, 0, 0, 0),
        SurefireReports.totals(reports, new SystemStreamLog()));
  }

  @Test
  void skipped_directories_are_not_descended_into(@TempDir Path root) throws Exception {
    writeReport(root.resolve("node_modules/some-pkg"), "TEST-Hidden.xml", suiteXml(1, 0, 0, 0));
    assertTrue(SurefireReports.sweep(root, null).isEmpty());
  }

  private static Path writeReport(Path module, String fileName, String content) throws IOException {
    Path reportsDir = module.resolve("target").resolve("surefire-reports");
    Files.createDirectories(reportsDir);
    Path report = reportsDir.resolve(fileName);
    Files.writeString(report, content);
    return report;
  }

  @Test
  void a_testcase_with_a_flaky_failure_child_counts_once_toward_the_flaky_total(@TempDir Path root)
      throws Exception {
    writeReport(
        root.resolve("mod-a"),
        "TEST-Flaky.xml",
        "<testsuite name=\"Example\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"0\">"
            + "<testcase name=\"steady\" classname=\"C\"/>"
            + "<testcase name=\"flaked_twice\" classname=\"C\">"
            + "<flakyFailure message=\"first\"/><flakyFailure message=\"second\"/>"
            + "</testcase>"
            + "<testcase name=\"flaked_on_error\" classname=\"C\">"
            + "<flakyError message=\"boom\"/>"
            + "</testcase>"
            + "</testsuite>");

    SurefireReports.Totals totals =
        SurefireReports.totals(SurefireReports.sweep(root, null), new SystemStreamLog());
    assertEquals(new SurefireReports.Totals(3, 0, 0, 0, 2), totals);
  }

  private static String suiteXml(int tests, int failures, int errors, int skipped) {
    return "<testsuite name=\"Example\" tests=\""
        + tests
        + "\" failures=\""
        + failures
        + "\" errors=\""
        + errors
        + "\" skipped=\""
        + skipped
        + "\"/>";
  }
}
