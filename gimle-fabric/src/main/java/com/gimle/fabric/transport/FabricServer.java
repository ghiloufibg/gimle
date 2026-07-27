package com.gimle.fabric.transport;

import com.gimle.module.lifecycle.ServiceRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of a cross-hop service call: accepts already-connected byte channels -- one
 * {@code ServerSocketChannel} bound to a {@link UnixDomainSocketAddress} for the same-machine tier,
 * another bound to a TCP {@link java.net.InetSocketAddress} for the cross-machine tier -- and
 * serves both through the identical request-handling loop, since the two paths differ only in which
 * socket accepted the connection, never in how frames are decoded or dispatched. One virtual thread
 * per accepted connection.
 */
public final class FabricServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FabricServer.class);

  private final ServiceRegistry localRegistry;
  private final ClassLoader interfaceLoader;
  private final List<ServerSocketChannel> listeners = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  public FabricServer(ServiceRegistry localRegistry, ClassLoader interfaceLoader) {
    this.localRegistry = localRegistry;
    this.interfaceLoader = interfaceLoader;
  }

  /**
   * Binds a listener at {@code bindAddress}, choosing UDS vs. TCP by the address type, and returns
   * the actual bound address (useful when {@code bindAddress} requested an ephemeral port/path).
   */
  public SocketAddress listen(SocketAddress bindAddress) throws IOException {
    ServerSocketChannel serverChannel =
        bindAddress instanceof UnixDomainSocketAddress
            ? ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            : ServerSocketChannel.open();
    serverChannel.bind(bindAddress);
    listeners.add(serverChannel);
    SocketAddress boundAddress = serverChannel.getLocalAddress();
    Thread.ofVirtual()
        .name("gimle-fabric-listener-" + boundAddress)
        .start(() -> acceptLoop(serverChannel));
    return boundAddress;
  }

  private void acceptLoop(ServerSocketChannel serverChannel) {
    while (!closed && serverChannel.isOpen()) {
      SocketChannel connection;
      try {
        connection = serverChannel.accept();
      } catch (IOException e) {
        if (!closed) {
          log.warn("fabric server accept loop failed: {}", e.getMessage());
        }
        return;
      }
      Thread.ofVirtual().name("gimle-fabric-connection").start(() -> serve(connection));
    }
  }

  private void serve(SocketChannel connection) {
    try (connection) {
      var in = Channels.newInputStream(connection);
      var out = Channels.newOutputStream(connection);
      FabricFrame frame;
      while ((frame = FabricCodec.read(in)) != null) {
        if (frame instanceof FabricFrame.InvokeRequest request) {
          FabricCodec.write(out, dispatch(request));
        } else {
          log.warn("fabric server received an unexpected frame type: {}", frame);
        }
      }
    } catch (IOException e) {
      log.debug("fabric connection closed: {}", e.getMessage());
    }
  }

  private FabricFrame dispatch(FabricFrame.InvokeRequest request) {
    Span span = startChildSpan(request);
    try (var scope = Context.current().with(span).makeCurrent()) {
      Object result = invokeLocally(request);
      span.setStatus(StatusCode.OK);
      return new FabricFrame.InvokeResponse(
          request.correlationId(), ObjectMarshalling.serialize(result));
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() != null ? e.getCause() : e;
      span.recordException(cause);
      span.setStatus(StatusCode.ERROR);
      return new FabricFrame.InvokeError(
          request.correlationId(), ObjectMarshalling.serialize(cause));
    } catch (RuntimeException | ReflectiveOperationException e) {
      span.recordException(e);
      span.setStatus(StatusCode.ERROR);
      return new FabricFrame.InvokeError(request.correlationId(), ObjectMarshalling.serialize(e));
    } finally {
      span.end();
    }
  }

  private Object invokeLocally(FabricFrame.InvokeRequest request)
      throws ReflectiveOperationException {
    Class<?> iface = Class.forName(request.interfaceName(), true, interfaceLoader);
    Optional<?> instance = localRegistry.lookup(iface);
    if (instance.isEmpty()) {
      throw new NoSuchElementException("no local service registered for " + iface.getName());
    }
    Class<?>[] paramTypes = new Class<?>[request.paramTypeNames().length];
    for (int i = 0; i < paramTypes.length; i++) {
      paramTypes[i] = resolveClass(request.paramTypeNames()[i]);
    }
    Method method = iface.getMethod(request.methodName(), paramTypes);
    Object[] args = (Object[]) ObjectMarshalling.deserialize(request.serializedArgs());
    return method.invoke(instance.get(), args);
  }

  private Class<?> resolveClass(String name) throws ClassNotFoundException {
    return switch (name) {
      case "boolean" -> boolean.class;
      case "byte" -> byte.class;
      case "short" -> short.class;
      case "char" -> char.class;
      case "int" -> int.class;
      case "long" -> long.class;
      case "float" -> float.class;
      case "double" -> double.class;
      case "void" -> void.class;
      default -> Class.forName(name, false, interfaceLoader);
    };
  }

  private Span startChildSpan(FabricFrame.InvokeRequest request) {
    var trace = request.trace();
    SpanContext remoteParent =
        SpanContext.createFromRemoteParent(
            traceIdHex(trace.traceIdHigh(), trace.traceIdLow()),
            spanIdHex(trace.spanId()),
            (trace.flags() & 1) != 0 ? TraceFlags.getSampled() : TraceFlags.getDefault(),
            TraceState.getDefault());
    return GlobalOpenTelemetry.getTracer("com.gimle.fabric")
        .spanBuilder(request.interfaceName() + "#" + request.methodName())
        .setSpanKind(SpanKind.SERVER)
        .setParent(Context.root().with(Span.wrap(remoteParent)))
        .startSpan();
  }

  static String traceIdHex(long high, long low) {
    return String.format("%016x%016x", high, low);
  }

  static String spanIdHex(long spanId) {
    return String.format("%016x", spanId);
  }

  @Override
  public void close() {
    closed = true;
    for (ServerSocketChannel channel : listeners) {
      try {
        channel.close();
      } catch (IOException e) {
        log.warn("failed to close fabric listener: {}", e.getMessage());
      }
    }
  }
}
