package com.gimle.hilmir.release;

import java.net.http.HttpHeaders;

/** A control-plane HTTP response, already read to a string body. */
record ApiResponse(int statusCode, String body, HttpHeaders headers) {

  boolean isSuccess() {
    return statusCode >= 200 && statusCode < 300;
  }
}
