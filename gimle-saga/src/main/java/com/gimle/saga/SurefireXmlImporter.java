package com.gimle.saga;

import com.gimle.core.hash.Sha256;
import com.gimle.core.saga.FailureSignature;
import com.gimle.core.saga.SagaEvent;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

/**
 * Folds Surefire/Failsafe XML reports into the same {@link SagaEvent} stream a live run would ship,
 * so historical results land in the store indistinguishably from ingested ones. Surefire's rerun
 * elements map directly onto attempts: each {@code flakyFailure}/{@code flakyError} is a failed
 * attempt before an eventual pass, each {@code rerunFailure}/{@code rerunError} a failed re-attempt
 * after an initial {@code failure}/{@code error} -- exactly the failed-then-passed shape the flake
 * ledger's derivation rule looks for. Uses only the JDK's own DOM parser.
 */
public final class SurefireXmlImporter {

  private static final int RUN_ID_HEX_LENGTH = 12;

  private SurefireXmlImporter() {}

  /** The events for one imported run; {@code runId} is deterministic for identical input. */
  public record ImportResult(String runId, List<SagaEvent> events) {
    public ImportResult {
      events = List.copyOf(events);
    }
  }

  /** Imports a single XML document sent as the request body itself. */
  public static ImportResult fromXmlDocument(String xml, String explicitRunId) {
    return fromXmlDocument(xml, explicitRunId, null);
  }

  /**
   * {@code explicitModule} names the module the report belongs to -- a raw document body carries no
   * file path to derive it from, and the module is the first segment of every testId, so a caller
   * that knows it should say so or the tests land under a module named {@code imported}.
   */
  public static ImportResult fromXmlDocument(
      String xml, String explicitRunId, String explicitModule) {
    String runId =
        explicitRunId != null
            ? explicitRunId
            : derivedRunId(List.of(xml.getBytes(StandardCharsets.UTF_8)));
    String module = explicitModule != null ? explicitModule : "imported";
    return assemble(runId, List.of(parseDocument(xml, module)));
  }

  /**
   * Imports every report file found under {@code paths} (files, or directories scanned for XML).
   */
  public static ImportResult fromPaths(List<Path> paths, String explicitRunId) {
    List<Path> files = expandToXmlFiles(paths);
    if (files.isEmpty()) {
      throw new IllegalArgumentException("no surefire XML report files found under: " + paths);
    }
    List<byte[]> contents = new ArrayList<>();
    List<ParsedSuite> suites = new ArrayList<>();
    for (Path file : files) {
      byte[] content;
      try {
        content = Files.readAllBytes(file);
      } catch (IOException e) {
        throw new UncheckedIOException("failed to read " + file, e);
      }
      contents.add(content);
      suites.add(parseDocument(new String(content, StandardCharsets.UTF_8), moduleNameFor(file)));
    }
    String runId = explicitRunId != null ? explicitRunId : derivedRunId(contents);
    return assemble(runId, suites);
  }

  // ---- parsing ----

  private record ParsedCase(String testId, List<Attempt> attempts) {}

  private record Attempt(
      SagaEvent.Outcome outcome,
      long durationMillis,
      Optional<String> failureType,
      Optional<String> failureMessage,
      Optional<String> failureSignature) {}

  private record ParsedSuite(List<ParsedCase> cases, long startedAtEpochMilli, String module) {}

  private static ParsedSuite parseDocument(String xml, String module) {
    Document document;
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      // Report files are plain data; refusing doctypes rules out XXE-style entity tricks.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      document = builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new IllegalArgumentException("unparseable surefire XML: " + e.getMessage(), e);
    }
    long startedAt = suiteTimestamp(document);
    List<ParsedCase> cases = new ArrayList<>();
    NodeList testcases = document.getElementsByTagName("testcase");
    for (int i = 0; i < testcases.getLength(); i++) {
      Element testcase = (Element) testcases.item(i);
      cases.add(parseTestCase(testcase, module));
    }
    return new ParsedSuite(cases, startedAt, module);
  }

  private static ParsedCase parseTestCase(Element testcase, String module) {
    String className = simpleClassName(testcase.getAttribute("classname"));
    String testId = module + ":" + className + "#" + testcase.getAttribute("name");
    long durationMillis = secondsToMillis(testcase.getAttribute("time"));

    Element skipped = null;
    Element primaryFailure = null;
    List<Element> flakyFailures = new ArrayList<>();
    List<Element> rerunFailures = new ArrayList<>();
    NodeList children = testcase.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      if (!(children.item(i) instanceof Element child)) {
        continue;
      }
      switch (child.getTagName()) {
        case "skipped" -> skipped = child;
        case "failure", "error" -> primaryFailure = child;
        case "flakyFailure", "flakyError" -> flakyFailures.add(child);
        case "rerunFailure", "rerunError" -> rerunFailures.add(child);
        default -> {}
      }
    }

    List<Attempt> attempts = new ArrayList<>();
    if (skipped != null) {
      attempts.add(
          new Attempt(
              SagaEvent.Outcome.SKIPPED,
              durationMillis,
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
    } else if (primaryFailure != null) {
      attempts.add(failedAttempt(primaryFailure, durationMillis));
      for (Element rerun : rerunFailures) {
        attempts.add(failedAttempt(rerun, 0L));
      }
    } else if (!flakyFailures.isEmpty()) {
      for (Element flaky : flakyFailures) {
        attempts.add(failedAttempt(flaky, 0L));
      }
      attempts.add(
          new Attempt(
              SagaEvent.Outcome.PASSED,
              durationMillis,
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
    } else {
      attempts.add(
          new Attempt(
              SagaEvent.Outcome.PASSED,
              durationMillis,
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
    }
    return new ParsedCase(testId, attempts);
  }

  private static Attempt failedAttempt(Element failure, long durationMillis) {
    String type = failure.getAttribute("type");
    String message = failure.getAttribute("message");
    String topFrame = topStackFrame(failure.getTextContent());
    return new Attempt(
        SagaEvent.Outcome.FAILED,
        durationMillis,
        type.isEmpty() ? Optional.empty() : Optional.of(type),
        message.isEmpty() ? Optional.empty() : Optional.of(message),
        Optional.of(FailureSignature.of(type, message.isEmpty() ? null : message, topFrame)));
  }

  private static String topStackFrame(String stackTrace) {
    if (stackTrace == null) {
      return null;
    }
    for (String line : stackTrace.split("\n")) {
      String stripped = line.strip();
      if (stripped.startsWith("at ")) {
        return stripped;
      }
    }
    return null;
  }

  private static long suiteTimestamp(Document document) {
    NodeList suites = document.getElementsByTagName("testsuite");
    for (int i = 0; i < suites.getLength(); i++) {
      String timestamp = ((Element) suites.item(i)).getAttribute("timestamp");
      if (!timestamp.isEmpty()) {
        try {
          return LocalDateTime.parse(timestamp).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (DateTimeParseException e) {
          // Fall through: not every producer writes ISO local-date-time here.
        }
      }
    }
    return System.currentTimeMillis();
  }

  // ---- assembly ----

  private static ImportResult assemble(String runId, List<ParsedSuite> suites) {
    long startedAt =
        suites.stream().mapToLong(ParsedSuite::startedAtEpochMilli).min().orElseThrow();
    Set<String> modules = new LinkedHashSet<>();
    List<SagaEvent> events = new ArrayList<>();
    int tests = 0;
    int passed = 0;
    int failed = 0;
    int skippedCount = 0;
    int flaky = 0;
    long totalDurationMillis = 0;
    List<SagaEvent> testEvents = new ArrayList<>();
    for (ParsedSuite suite : suites) {
      modules.add(suite.module());
      for (ParsedCase parsedCase : suite.cases()) {
        tests++;
        int attemptNumber = 0;
        for (Attempt attempt : parsedCase.attempts()) {
          attemptNumber++;
          testEvents.add(
              new SagaEvent.TestStarted(runId, parsedCase.testId(), List.of(), attemptNumber));
          testEvents.add(
              new SagaEvent.TestFinished(
                  runId,
                  parsedCase.testId(),
                  attemptNumber,
                  attempt.outcome(),
                  attempt.durationMillis(),
                  attempt.failureType(),
                  attempt.failureMessage(),
                  attempt.failureSignature()));
          totalDurationMillis += attempt.durationMillis();
        }
        Attempt last = parsedCase.attempts().get(parsedCase.attempts().size() - 1);
        switch (last.outcome()) {
          case PASSED -> passed++;
          case FAILED -> failed++;
          case SKIPPED -> skippedCount++;
          case ABORTED -> {}
        }
        if (last.outcome() == SagaEvent.Outcome.PASSED && parsedCase.attempts().size() > 1) {
          flaky++;
        }
      }
    }
    events.add(
        new SagaEvent.RunStarted(
            runId, startedAt, "", "", "surefire-import", List.copyOf(modules)));
    events.addAll(testEvents);
    events.add(
        new SagaEvent.RunFinished(
            runId,
            startedAt + totalDurationMillis,
            new SagaEvent.RunTotals(tests, passed, failed, skippedCount, flaky)));
    return new ImportResult(runId, events);
  }

  // ---- helpers ----

  private static List<Path> expandToXmlFiles(List<Path> paths) {
    List<Path> files = new ArrayList<>();
    for (Path path : paths) {
      if (Files.isDirectory(path)) {
        try (Stream<Path> entries = Files.list(path)) {
          entries
              .filter(Files::isRegularFile)
              .filter(p -> fileNameString(p).endsWith(".xml"))
              .sorted()
              .forEach(files::add);
        } catch (IOException e) {
          throw new UncheckedIOException("failed to list " + path, e);
        }
      } else if (Files.isRegularFile(path)) {
        files.add(path);
      } else {
        throw new IllegalArgumentException("no such file or directory: " + path);
      }
    }
    return files;
  }

  /**
   * A surefire-reports path is conventionally {@code <module>/target/surefire-reports/...}, so the
   * segment before {@code target} names the Maven module; falls back to the file's own directory
   * name when the path has no {@code target} segment.
   */
  private static String moduleNameFor(Path file) {
    Path absolute = file.toAbsolutePath();
    for (int i = 1; i < absolute.getNameCount(); i++) {
      if ("target".equals(absolute.getName(i).toString())) {
        return absolute.getName(i - 1).toString();
      }
    }
    Path parent = absolute.getParent();
    return parent == null ? "imported" : fileNameString(parent);
  }

  // getFileName() only returns null for a zero-element path, which never reaches these call sites;
  // the guard lives here once rather than at each of them.
  private static String fileNameString(Path path) {
    Path fileName = path.getFileName();
    if (fileName == null) {
      throw new IllegalStateException("expected a named path, got " + path);
    }
    return fileName.toString();
  }

  private static long secondsToMillis(String seconds) {
    if (seconds == null || seconds.isBlank()) {
      return 0L;
    }
    try {
      return Math.round(Double.parseDouble(seconds.replace(",", "")) * 1000.0);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }

  private static String simpleClassName(String className) {
    int lastDot = className.lastIndexOf('.');
    return lastDot < 0 ? className : className.substring(lastDot + 1);
  }

  private static String derivedRunId(List<byte[]> contents) {
    MessageDigest digest = Sha256.sha256Digest();
    for (byte[] content : contents) {
      digest.update(content);
    }
    return "import-" + HexFormat.of().formatHex(digest.digest()).substring(0, RUN_ID_HEX_LENGTH);
  }
}
