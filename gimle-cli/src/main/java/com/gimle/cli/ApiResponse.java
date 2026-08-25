package com.gimle.cli;

import java.util.List;

/**
 * A control-plane HTTP response, already read to a string body, plus any {@code X-Gimle-Warning}
 * headers the server attached -- deprecation warnings a manifest PUT surfaces back to the
 * submitting operator (printed on stderr, never mixed into stdout's own {@code -o json} output).
 */
public record ApiResponse(int statusCode, String body, List<String> warnings) {

  public ApiResponse {
    warnings = List.copyOf(warnings);
  }

  public ApiResponse(int statusCode, String body) {
    this(statusCode, body, List.of());
  }

  public boolean isSuccess() {
    return statusCode >= 200 && statusCode < 300;
  }
}
