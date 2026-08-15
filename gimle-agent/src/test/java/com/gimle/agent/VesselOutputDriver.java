package com.gimle.agent;

/**
 * A minimal stand-in for a vessel's own runnable jar, used by {@link VesselProcessSupervisorTest}
 * to exercise real stdout capture without needing an actual packaged jar: prints {@code args[0]} to
 * stdout, then sleeps for {@code args[1]} milliseconds so the test has time to read the captured
 * line and deliberately stop the supervisor before this process would otherwise exit on its own.
 */
public final class VesselOutputDriver {

  private VesselOutputDriver() {}

  public static void main(String[] args) throws InterruptedException {
    System.out.println(args[0]);
    Thread.sleep(Long.parseLong(args[1]));
  }
}
