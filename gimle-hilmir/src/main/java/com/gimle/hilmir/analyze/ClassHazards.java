package com.gimle.hilmir.analyze;

import java.util.List;

/**
 * Per-class hazard signals found by a linear scan of every method's own instruction stream -- known
 * failure-class tripwires (calling {@code System.exit}, registering a shutdown hook, spawning a
 * non-daemon thread, loading a native library, opening a server socket, making an outbound
 * connection, or leaving a static executor with no visible shutdown call anywhere in the class),
 * never a control-flow or dataflow analysis. A clean class produces every boolean flag {@code
 * false}. {@code declaredInterfaces} and {@code hasNoArgConstructor} are used by the probe/hooks
 * validity check, not the hazard findings.
 */
public record ClassHazards(
    String binaryName,
    boolean callsSystemExit,
    boolean registersShutdownHook,
    boolean constructsNonDaemonThread,
    boolean loadsNativeLibrary,
    boolean opensServerSocket,
    boolean makesOutboundConnection,
    boolean hasUnshutdownStaticExecutor,
    List<String> declaredInterfaces,
    boolean hasNoArgConstructor) {

  public ClassHazards {
    declaredInterfaces = List.copyOf(declaredInterfaces);
  }

  public boolean isClean() {
    return !callsSystemExit
        && !registersShutdownHook
        && !constructsNonDaemonThread
        && !loadsNativeLibrary
        && !opensServerSocket
        && !makesOutboundConnection
        && !hasUnshutdownStaticExecutor;
  }
}
