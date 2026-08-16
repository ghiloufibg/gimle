package com.gimle.mavenplugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SagaVerifyMojoTest {

  @Test
  void run_id_joins_timestamp_and_short_sha() {
    assertEquals(
        "2026-08-16T10-30-05_abc1234",
        SagaVerifyMojo.mintRunId(LocalDateTime.of(2026, 8, 16, 10, 30, 5), Optional.of("abc1234")));
  }

  @Test
  void run_id_falls_back_to_local_when_git_is_absent() {
    assertEquals(
        "2026-08-16T10-30-05_local",
        SagaVerifyMojo.mintRunId(LocalDateTime.of(2026, 8, 16, 10, 30, 5), Optional.empty()));
  }

  @Test
  void child_command_defaults_to_verify_and_threads_the_saga_properties() {
    assertEquals(
        List.of(
            "mvn",
            "verify",
            "-Dgimle.saga.endpoint=http://127.0.0.1:9096",
            "-Dgimle.saga.runId=2026-08-16T10-30-05_abc1234"),
        SagaVerifyMojo.childMavenCommand(
            "mvn", "verify", "http://127.0.0.1:9096", "2026-08-16T10-30-05_abc1234"));
  }

  @Test
  void child_command_splits_whitespace_separated_maven_args() {
    assertEquals(
        List.of(
            "mvn",
            "clean",
            "verify",
            "-Psmoke",
            "-Dgimle.saga.endpoint=http://127.0.0.1:9096",
            "-Dgimle.saga.runId=r1"),
        SagaVerifyMojo.childMavenCommand(
            "mvn", "  clean   verify -Psmoke ", "http://127.0.0.1:9096", "r1"));
  }
}
