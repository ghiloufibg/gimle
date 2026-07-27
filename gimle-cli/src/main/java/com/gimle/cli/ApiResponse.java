package com.gimle.cli;

/** A control-plane HTTP response, already read to a string body. */
public record ApiResponse(int statusCode, String body) {

  public boolean isSuccess() {
    return statusCode >= 200 && statusCode < 300;
  }
}
