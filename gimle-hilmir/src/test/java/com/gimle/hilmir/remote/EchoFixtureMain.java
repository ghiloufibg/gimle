package com.gimle.hilmir.remote;

/**
 * A minimal standalone process for {@link SshProcessExecTest}: writes a fixed message to either
 * stdout or stderr and exits, standing in for a real {@code ssh-keyscan} without shelling out to a
 * POSIX {@code sh} that isn't guaranteed to be on {@code PATH} (notably on plain Windows without
 * Git/WSL) the way it is on Linux/macOS.
 */
public final class EchoFixtureMain {

  private EchoFixtureMain() {}

  public static void main(final String[] args) {
    final String stream = args[0];
    final String message = args[1];
    if (stream.equals("stderr")) {
      System.err.println(message);
      System.exit(1);
    } else {
      System.out.println(message);
    }
  }
}
