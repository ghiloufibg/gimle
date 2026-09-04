package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.core.saga.SagaEvent;
import com.gimle.core.saga.SagaEventCodec;
import com.sun.net.httpserver.HttpServer;
import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SagaImportMojo#executeAtRoot()} needs a live Maven session to run at all, but a fake Saga
 * server standing in for the real one lets the whole flow run for real otherwise -- real surefire
 * XML on disk, real HTTP calls -- without the actual {@code gimle-saga} process. Two real bugs
 * regression-tested here: a multi-module {@code -pl} invocation silently sweeping only one module's
 * own reports (GIMLE saga-import), and the persisted run summary reflecting only the first imported
 * report's own totals instead of every swept report's combined totals.
 */
class SagaImportMojoTest {

  private HttpServer server;
  private final List<String> importedQueries = new CopyOnWriteArrayList<>();
  private final List<String> ingestedLines = new CopyOnWriteArrayList<>();

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  void sweeps_every_module_under_the_reactor_root_not_just_the_orchestration_projects_own_dir(
      @TempDir Path repoRoot) throws Exception {
    writeSurefireReport(repoRoot.resolve("gimle-os"), "TEST-A.xml", suiteXml(2, 0, 0, 0));
    writeSurefireReport(repoRoot.resolve("gimle-core"), "TEST-B.xml", suiteXml(3, 1, 0, 0));
    writeSurefireReport(repoRoot.resolve("gimle-mimir"), "TEST-C.xml", suiteXml(5, 0, 1, 0));
    startFakeSagaServer();

    SagaImportMojo mojo = mojoFor(repoRoot, "gimle-os", "gimle-core", "gimle-mimir");
    mojo.runId = "r1";
    mojo.execute();

    assertEquals(3, importedQueries.size(), "every module's own report must be imported");

    SagaEvent.RunFinished runFinished = onlyRunFinished();
    assertEquals(new SagaEvent.RunTotals(10, 8, 2, 0, 0), runFinished.totals());
  }

  @Test
  void the_persisted_run_finished_totals_sum_every_swept_report_not_just_the_first(
      @TempDir Path repoRoot) throws Exception {
    for (int i = 0; i < 5; i++) {
      writeSurefireReport(
          repoRoot.resolve("gimle-core"), "TEST-Class" + i + ".xml", suiteXml(10, 0, 0, 0));
    }
    startFakeSagaServer();

    SagaImportMojo mojo = mojoFor(repoRoot, "gimle-core");
    mojo.runId = "r2";
    mojo.execute();

    SagaEvent.RunFinished runFinished = onlyRunFinished();
    // Wrong before the fix: a run-finished derived from only the first-processed file's own
    // testsuite attributes would report 10 tests here, not the real 50 across all five files.
    assertEquals(50, runFinished.totals().tests());
    assertEquals(50, runFinished.totals().passed());
  }

  private SagaEvent.RunFinished onlyRunFinished() {
    List<SagaEvent.RunFinished> finished = new ArrayList<>();
    for (String line : ingestedLines) {
      if (SagaEventCodec.decode(line) instanceof SagaEvent.RunFinished runFinished) {
        finished.add(runFinished);
      }
    }
    assertEquals(1, finished.size(), "exactly one run-finished event must be posted");
    return finished.get(0);
  }

  private void startFakeSagaServer() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/api/health",
        exchange -> {
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/api/import",
        exchange -> {
          importedQueries.add(exchange.getRequestURI().getQuery());
          exchange.getRequestBody().readAllBytes();
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.createContext(
        "/api/ingest",
        exchange -> {
          String body =
              new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          for (String line : body.split("\n")) {
            if (!line.isBlank()) {
              ingestedLines.add(line);
            }
          }
          exchange.sendResponseHeaders(200, -1);
          exchange.close();
        });
    server.start();
  }

  private SagaImportMojo mojoFor(Path repoRoot, String... reactorModuleArtifactIds)
      throws Exception {
    DefaultMavenExecutionRequest request = new DefaultMavenExecutionRequest();
    request.setBaseDirectory(repoRoot.toFile());
    List<MavenProject> projects = new ArrayList<>();
    for (String artifactId : reactorModuleArtifactIds) {
      MavenProject project = new MavenProject();
      project.setArtifactId(artifactId);
      project.setFile(new File(repoRoot.resolve(artifactId).toFile(), "pom.xml"));
      projects.add(project);
    }
    MavenSession session = new MavenSession(null, request, null, List.copyOf(projects));

    SagaImportMojo mojo = new SagaImportMojo();
    mojo.project = projects.get(0);
    mojo.session = session;
    mojo.port = String.valueOf(server.getAddress().getPort());
    return mojo;
  }

  private static void writeSurefireReport(Path module, String fileName, String content)
      throws Exception {
    Path reportsDir = module.resolve("target").resolve("surefire-reports");
    Files.createDirectories(reportsDir);
    Files.writeString(reportsDir.resolve(fileName), content);
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
