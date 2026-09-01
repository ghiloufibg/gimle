package com.gimle.fabric.registry;

import com.gimle.module.lifecycle.Idempotent;

/**
 * The {@link Greeter} counterpart whose one method declares itself safe to repeat -- the only
 * difference that decides whether a failure after the request was already sent may be retried.
 */
public interface RetryableGreeter {

  @Idempotent
  String greet(String name);
}
