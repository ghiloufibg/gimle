package com.gimle.ragnarok.pause;

/**
 * The trivial payload of this bundled reference module -- exists only so the jar contains a real
 * class file alongside {@code module-info.class}, matching what a genuine hosted module looks like.
 * Nothing in Gimle ever calls this directly: this module declares no {@code health}/{@code
 * lifecycle} sections in its descriptor (both optional), so the platform never resolves a class
 * here at all.
 */
public final class Pause {

  private Pause() {}

  public static String name() {
    return "com.gimle.ragnarok.pause";
  }
}
