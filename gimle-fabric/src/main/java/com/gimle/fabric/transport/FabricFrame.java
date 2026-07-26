package com.gimle.fabric.transport;

import com.gimle.fabric.trace.TraceContext;

/**
 * A frame on the fabric's own per-invocation wire protocol (Phase 4 §7) -- deliberately not {@code
 * gimle-core}'s {@code ControlMessageCodec} line-oriented framing, which was designed for a handful
 * of lifecycle commands, not per-request service traffic carrying arbitrary serialized bytes.
 */
public sealed interface FabricFrame {

  long correlationId();

  /**
   * A cross-hop service call: {@code paramTypeNames} names each parameter's declared type (not the
   * runtime type of {@code serializedArgs}' contents) so the receiving end can resolve the exact
   * method overload via reflection; {@code serializedArgs} is a single {@code Object[]} of the call
   * arguments, marshaled with {@code ObjectOutputStream} by the proxy's {@code InvocationHandler}
   * (§6).
   */
  record InvokeRequest(
      long correlationId,
      TraceContext trace,
      String interfaceName,
      String methodName,
      String[] paramTypeNames,
      byte[] serializedArgs)
      implements FabricFrame {}

  /** A successful invocation's return value, {@code ObjectOutputStream}-serialized. */
  record InvokeResponse(long correlationId, byte[] serializedReturn) implements FabricFrame {}

  /**
   * The invoked method threw: the original {@link Throwable} itself, serialized, so the proxy-side
   * caller can re-throw it with its real type/message/stack trace intact rather than a wrapped
   * remote-call exception (§6).
   */
  record InvokeError(long correlationId, byte[] serializedThrowable) implements FabricFrame {}
}
