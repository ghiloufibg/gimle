package com.gimle.hilmir.release;

/** A control-plane HTTP response, already read to a string body. */
record ApiResponse(int statusCode, String body) {

  boolean isSuccess() {
    return statusCode >= 200 && statusCode < 300;
  }
}
