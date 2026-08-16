package com.gimle.mavenplugin;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/** Discovery and totals-parsing over {@code **}{@code /target/surefire-reports/*.xml} files. */
final class SurefireReports {

  record Totals(int tests, int failures, int errors, int skipped, int flaky) {
    static final Totals ZERO = new Totals(0, 0, 0, 0, 0);

    Totals plus(Totals other) {
      return new Totals(
          tests + other.tests,
          failures + other.failures,
          errors + other.errors,
          skipped + other.skipped,
          flaky + other.flaky);
    }
  }

  private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "node_modules");

  private SurefireReports() {}

  /**
   * Every {@code target/surefire-reports/*.xml} under {@code root}, sorted for a stable order;
   * {@code modifiedAtOrAfter == null} means no timestamp filter.
   */
  static List<Path> sweep(Path root, Instant modifiedAtOrAfter) throws MojoExecutionException {
    List<Path> found = new ArrayList<>();
    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
              if (SKIPPED_DIRECTORIES.contains(fileName(dir))) {
                return FileVisitResult.SKIP_SUBTREE;
              }
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (isSurefireReport(file)
                  && (modifiedAtOrAfter == null
                      || !attrs.lastModifiedTime().toInstant().isBefore(modifiedAtOrAfter))) {
                found.add(file);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      throw new MojoExecutionException("failed to sweep surefire reports under " + root, e);
    }
    found.sort(null);
    return List.copyOf(found);
  }

  private static boolean isSurefireReport(Path file) {
    Path parent = file.getParent();
    Path grandparent = parent == null ? null : parent.getParent();
    return fileName(file).endsWith(".xml")
        && parent != null
        && fileName(parent).equals("surefire-reports")
        && grandparent != null
        && fileName(grandparent).equals("target");
  }

  /** A path's last segment, or "" for a root path (whose {@code getFileName()} is null). */
  private static String fileName(Path path) {
    Path name = path.getFileName();
    return name == null ? "" : name.toString();
  }

  /** Sums each parseable report's counts; an unparseable file is warned about and skipped. */
  static Totals totals(Collection<Path> reports, Log log) {
    Totals sum = Totals.ZERO;
    for (Path report : reports) {
      Optional<Totals> parsed = parse(report, log);
      if (parsed.isPresent()) {
        sum = sum.plus(parsed.get());
      }
    }
    return sum;
  }

  private static Optional<Totals> parse(Path report, Log log) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      Element root = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
      if (root.getTagName().equals("testsuite")) {
        return Optional.of(totalsOf(root));
      }
      if (root.getTagName().equals("testsuites")) {
        Totals sum = Totals.ZERO;
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
          Node child = children.item(i);
          if (child instanceof Element element && element.getTagName().equals("testsuite")) {
            sum = sum.plus(totalsOf(element));
          }
        }
        return Optional.of(sum);
      }
      log.warn("skipping " + report + ": unexpected root element <" + root.getTagName() + ">");
      return Optional.empty();
    } catch (IOException | SAXException | ParserConfigurationException e) {
      log.warn("skipping unparseable surefire report " + report + ": " + e.getMessage());
      return Optional.empty();
    }
  }

  private static Totals totalsOf(Element testsuite) {
    return new Totals(
        intAttribute(testsuite, "tests"),
        intAttribute(testsuite, "failures"),
        intAttribute(testsuite, "errors"),
        intAttribute(testsuite, "skipped"),
        flakyTestcaseCount(testsuite));
  }

  /**
   * A testcase carrying at least one {@code <flakyFailure>}/{@code <flakyError>} child failed and
   * then passed on a Surefire rerun -- counted once per testcase, not per rerun attempt.
   */
  private static int flakyTestcaseCount(Element testsuite) {
    int flaky = 0;
    NodeList testcases = testsuite.getElementsByTagName("testcase");
    for (int i = 0; i < testcases.getLength(); i++) {
      Element testcase = (Element) testcases.item(i);
      if (testcase.getElementsByTagName("flakyFailure").getLength() > 0
          || testcase.getElementsByTagName("flakyError").getLength() > 0) {
        flaky++;
      }
    }
    return flaky;
  }

  private static int intAttribute(Element element, String name) {
    try {
      return Integer.parseInt(element.getAttribute(name));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
