package com.gimle.fabric.transport;

import com.gimle.fabric.trace.TraceContext;
import java.util.Optional;

/**
 * A frame on the fabric's own per-invocation wire protocol -- deliberately not {@code gimle-core}'s
 * {@code ControlMessageCodec} line-oriented framing, which was designed for a handful of lifecycle
 * commands, not per-request service traffic carrying arbitrary serialized bytes.
 */
public sealed interface FabricFrame {

  long correlationId();

  /**
   * A cross-hop service call: {@code paramTypeNames} names each parameter's declared type (not the
   * runtime type of {@code serializedArgs}' contents) so the receiving end can resolve the exact
   * method overload via reflection; {@code serializedArgs} is a single {@code Object[]} of the call
   * arguments, marshaled with {@code ObjectOutputStream} by the proxy's {@code InvocationHandler}.
   * {@code callerTenantId} is the calling worker's own {@code selfTenantId}, absent for an
   * untenanted caller -- carried on the wire so the receiving {@code FabricServer} can
   * independently re-check a target's own declared tenant restrictions, rather than trusting that
   * only {@code FabricServiceRegistry}'s caller-side filter ever produced this request (a caller
   * that dials a {@code ServiceEndpoint}'s raw address directly, obtained straight from the
   * gossip-replicated catalog, skips that filter entirely today).
   */
  record InvokeRequest(
      long correlationId,
      TraceContext trace,
      String interfaceName,
      String methodName,
      String[] paramTypeNames,
      byte[] serializedArgs,
      Optional<String> callerTenantId)
      implements FabricFrame {

    public InvokeRequest {
      if (callerTenantId == null) {
        throw new IllegalArgumentException("callerTenantId must be Optional.empty(), not null");
      }
    }

    /**
     * The same call with {@code callerTenantId} replaced -- how {@code FabricServer} substitutes
     * the tenant its connection's verified client certificate certifies for whatever the caller
     * wrote.
     */
    public InvokeRequest withCallerTenantId(Optional<String> certifiedTenantId) {
      return new InvokeRequest(
          correlationId,
          trace,
          interfaceName,
          methodName,
          paramTypeNames,
          serializedArgs,
          certifiedTenantId);
    }

    /** Back-compat: defaults {@code callerTenantId} to {@code Optional.empty()}. */
    public InvokeRequest(
        long correlationId,
        TraceContext trace,
        String interfaceName,
        String methodName,
        String[] paramTypeNames,
        byte[] serializedArgs) {
      this(
          correlationId,
          trace,
          interfaceName,
          methodName,
          paramTypeNames,
          serializedArgs,
          Optional.empty());
    }
  }

  /**
   * A successful invocation's return value, {@code ObjectOutputStream}-serialized, plus the
   * target's own reading of how much inbound work is queued ahead of the next request it receives.
   *
   * <p>That second field is what lets a caller balance on the target's real backlog rather than
   * only on the requests it has in flight there itself. The two are not the same thing and the
   * difference is the whole point: a replica saturated by *other* callers looks completely idle to
   * a client counting its own outstanding requests, so traffic keeps arriving until calls start
   * failing and the circuit breaker trips -- a failure the caller sees, where a load-based handoff
   * to a less busy replica would have been invisible. Reported on the response rather than polled
   * out of band so it costs nothing and is never staler than the last call actually made.
   */
  record InvokeResponse(long correlationId, byte[] serializedReturn, int reportedQueueDepth)
      implements FabricFrame {}

  /**
   * The invoked method threw. Carries the original {@link Throwable} serialized, so the proxy-side
   * caller can re-throw it with its real type/message/stack trace intact rather than a wrapped
   * remote-call exception -- and, alongside it, that exception's own binary type name and message
   * as plain text.
   *
   * <p>Those two plain-text fields are what keep a remote application failure legible when the
   * object itself cannot cross, which is not an edge case: a hosted module's exception type is
   * routinely private to that module's own layer and so unloadable in the caller, and an exception
   * reaching a non-serializable field cannot be written at all. Without them the first case failed
   * to decode at the caller and the second produced no frame whatsoever -- both surfacing as
   * something indistinguishable from a broken wire, for a call the target had answered perfectly
   * clearly. {@code serializedThrowable} is empty exactly when the throwable could not be
   * serialized.
   */
  record InvokeError(
      long correlationId,
      String remoteTypeName,
      Optional<String> remoteMessage,
      byte[] serializedThrowable,
      int reportedQueueDepth)
      implements FabricFrame {

    public InvokeError {
      if (remoteMessage == null) {
        throw new IllegalArgumentException("remoteMessage must be Optional.empty(), not null");
      }
    }

    /**
     * The frame reporting {@code cause}, degrading to type name and message alone when {@code
     * cause} cannot be serialized. A target that threw must always produce an answer its caller can
     * read: failing to build the frame would drop the connection instead, leaving the caller to
     * report a transport failure for something that was nothing of the kind.
     */
    public static InvokeError of(long correlationId, Throwable cause, int reportedQueueDepth) {
      return new InvokeError(
          correlationId,
          cause.getClass().getName(),
          Optional.ofNullable(cause.getMessage()),
          ObjectMarshalling.serializeIfPossible(cause),
          reportedQueueDepth);
    }

    /**
     * For an error raised before the call ever reached a module -- an unresolvable service, a
     * rejected tenant -- where there is no target whose backlog could be reported.
     */
    public static InvokeError of(long correlationId, Throwable cause) {
      return of(correlationId, cause, 0);
    }

    /**
     * The exception the caller of this frame should raise: the target's own throwable whenever
     * {@code loader} can rebuild it, so a caller that shares the type keeps catching it by type;
     * otherwise a {@link RemoteInvocationException} naming what the target threw and what it said.
     */
    public Throwable toThrowable(ClassLoader loader) {
      if (serializedThrowable.length > 0) {
        try {
          if (ObjectMarshalling.deserialize(serializedThrowable, loader) instanceof Throwable t) {
            return t;
          }
        } catch (RuntimeException e) {
          // The target's exception type is not loadable here -- commonly because it is private to
          // the provider module's own layer. Its name and message still say what happened.
        }
      }
      return new RemoteInvocationException(remoteTypeName, remoteMessage);
    }
  }
}
