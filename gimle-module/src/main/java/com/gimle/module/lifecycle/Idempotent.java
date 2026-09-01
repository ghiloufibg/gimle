package com.gimle.module.lifecycle;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a service-interface method safe to execute more than once with the same arguments, so
 * the fabric may retry it after a failure that leaves the call's outcome genuinely unknown -- a
 * connection that dropped after the request was written but before a response came back, or a call
 * that exceeded the client's own deadline.
 *
 * <p>Without this annotation a method is treated as unsafe to retry once the request has been sent:
 * the target may have already executed it, and re-executing a non-idempotent mutation is worse than
 * surfacing the failure. A failure that provably happened <em>before</em> the request reached the
 * target (the connection was never established) is retried against a different endpoint either way
 * -- nothing ran, so nothing can run twice, whether or not this annotation is present.
 *
 * <p>Retrying still carries the original request's correlation id, so a target that did in fact
 * execute the first attempt answers the retry from its own duplicate-suppression window rather than
 * running the method a second time. That makes the retry safe in practice even for a method whose
 * author was being optimistic -- but the annotation remains a declaration by the method's author,
 * not something the platform infers, because the suppression window is finite and a retry arriving
 * after it expires really does execute again.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Idempotent {}
