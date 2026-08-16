package com.gimle.mavenplugin;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * A canned {@link Process} result for exercising wait/decision logic without spawning anything:
 * either still "running" (alive, no exit value yet) or already finished with a fixed exit code.
 */
final class FakeProcess extends Process {

  static FakeProcess alive() {
    return new FakeProcess(true, 0);
  }

  static FakeProcess exited(int exitCode) {
    return new FakeProcess(false, exitCode);
  }

  private final boolean alive;
  private final int exitCode;

  private FakeProcess(boolean alive, int exitCode) {
    this.alive = alive;
    this.exitCode = exitCode;
  }

  @Override
  public OutputStream getOutputStream() {
    return OutputStream.nullOutputStream();
  }

  @Override
  public InputStream getInputStream() {
    return InputStream.nullInputStream();
  }

  @Override
  public InputStream getErrorStream() {
    return InputStream.nullInputStream();
  }

  @Override
  public int waitFor() {
    if (alive) {
      throw new IllegalStateException("this fake never finishes; don't wait on it");
    }
    return exitCode;
  }

  @Override
  public int exitValue() {
    if (alive) {
      throw new IllegalThreadStateException("still running");
    }
    return exitCode;
  }

  @Override
  public void destroy() {}

  @Override
  public boolean isAlive() {
    return alive;
  }
}
