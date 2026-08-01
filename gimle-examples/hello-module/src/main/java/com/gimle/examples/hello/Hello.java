package com.gimle.examples.hello;

/**
 * The trivial payload of this example module -- exists only so the jar contains a real class file
 * alongside {@code module-info.class}, matching what a genuine hosted module looks like. Nothing in
 * Gimle ever calls this directly: this module declares no {@code health}/{@code lifecycle} sections
 * in its descriptor (both optional), so the platform never resolves a class here at all.
 */
public final class Hello {

  private Hello() {}

  public static String greeting() {
    return "hello from com.gimle.examples.hello";
  }
}
