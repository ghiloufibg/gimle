package com.gimle.fabric.transport;

import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.module.lifecycle.ServiceRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
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
import javax.net.ssl.SSLServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of a cross-hop service call: accepts already-connected byte channels -- one
 * {@code ServerSocketChannel} bound to a {@link UnixDomainSocketAddress} for the same-machine tier
 * (always plaintext -- kernel-mediated, never leaves the machine, per {@code
 * claudedocs/tls-transport-security-design.md} §1's own table), another bound to a TCP {@link
 * java.net.InetSocketAddress} for the cross-machine tier, gated on {@link
 * TransportProtocol#fromConfig()} between a plain {@code ServerSocketChannel} and a TLS {@link
 * SSLServerSocket} (the same reasoning and code shape as {@code RaftTransport}'s own swap, since
 * JSSE's classic API has no NIO-channel equivalent) -- and serves all three through the identical
 * request-handling loop once reduced to an {@link InputStream}/{@link OutputStream} pair, since
 * they differ only in which socket accepted the connection, never in how frames are decoded or
 * dispatched. One virtual thread per accepted connection.
 */
public final class FabricServer implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(FabricServer.class);

  private final ServiceRegistry localRegistry;
  private final ClassLoader interfaceLoader;
  private final List<Closeable> listeners = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  public FabricServer(ServiceRegistry localRegistry, ClassLoader interfaceLoader) {
    this.localRegistry = localRegistry;
    this.interfaceLoader = interfaceLoader;
  }

  /**
   * Binds a listener at {@code bindAddress}, choosing UDS vs. TCP by the address type (TCP further
   * gated on {@link TransportProtocol#fromConfig()}), and returns the actual bound address (useful
   * when {@code bindAddress} requested an ephemeral port/path).
   */
  public SocketAddress listen(SocketAddress bindAddress) throws IOException {
    if (bindAddress instanceof UnixDomainSocketAddress) {
      return listenChannel(ServerSocketChannel.open(StandardProtocolFamily.UNIX), bindAddress);
    }
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return listenChannel(ServerSocketChannel.open(), bindAddress);
    }
    return listenTls(bindAddress);
  }

  private SocketAddress listenChannel(ServerSocketChannel serverChannel, SocketAddress bindAddress)
      throws IOException {
    serverChannel.bind(bindAddress);
    listeners.add(serverChannel);
    SocketAddress boundAddress = serverChannel.getLocalAddress();
    Thread.ofVirtual()
        .name("gimle-fabric-listener-" + boundAddress)
        .start(() -> acceptChannelLoop(serverChannel));
    return boundAddress;
  }

  private SocketAddress listenTls(SocketAddress bindAddress) throws IOException {
    SSLServerSocket serverSocket =
        (SSLServerSocket)
            SslContexts.forMutualTls(TlsSettings.fromConfig())
                .getServerSocketFactory()
                .createServerSocket();
    serverSocket.setNeedClientAuth(true);
    serverSocket.bind(bindAddress);
    listeners.add(serverSocket);
    SocketAddress boundAddress = serverSocket.getLocalSocketAddress();
    Thread.ofVirtual()
        .name("gimle-fabric-listener-" + boundAddress)
        .start(() -> acceptSocketLoop(serverSocket));
    return boundAddress;
  }

  private void acceptChannelLoop(ServerSocketChannel serverChannel) {
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

  private void acceptSocketLoop(ServerSocket serverSocket) {
    while (!closed && !serverSocket.isClosed()) {
      Socket connection;
      try {
        connection = serverSocket.accept();
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
      serveStreams(Channels.newInputStream(connection), Channels.newOutputStream(connection));
    } catch (IOException e) {
      log.debug("fabric connection closed: {}", e.getMessage());
    }
  }

  private void serve(Socket connection) {
    try (connection) {
      serveStreams(connection.getInputStream(), connection.getOutputStream());
    } catch (IOException e) {
      log.debug("fabric connection closed: {}", e.getMessage());
    }
  }

  private void serveStreams(InputStream in, OutputStream out) throws IOException {
    FabricFrame frame;
    while ((frame = FabricCodec.read(in)) != null) {
      if (frame instanceof FabricFrame.InvokeRequest request) {
        FabricCodec.write(out, dispatch(request));
      } else {
        log.warn("fabric server received an unexpected frame type: {}", frame);
      }
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
    // Looked up by name, not Class.forName(name, true, interfaceLoader): the interface is
    // typically private to one hosted module's own layer (gimle-api doesn't exist yet to host a
    // service contract on a shared platform layer every worker-wide interfaceLoader can resolve),
    // so a single fixed loader can't be relied on to see it. The registry already holds the
    // provider's instance keyed by the exact Class its own module registered it under -- no
    // separate resolution needed.
    Optional<?> instance = localRegistry.lookupByInterfaceName(request.interfaceName());
    if (instance.isEmpty()) {
      throw new NoSuchElementException(
          "no local service registered for " + request.interfaceName());
    }
    Class<?>[] paramTypes = new Class<?>[request.paramTypeNames().length];
    for (int i = 0; i < paramTypes.length; i++) {
      paramTypes[i] = resolveClass(request.paramTypeNames()[i]);
    }
    // The Method must come from the public interface Class, not instance.get().getClass()
    // directly: a lambda or InstanceMdcContext MDC-tagging proxy implementing the interface is
    // itself package-private/synthetic, and Method#invoke checks the *declaring class*'s
    // accessibility, not just the method's own public modifier -- reflecting through the
    // interface (which the registering module's own Class.getInterfaces() always exposes as the
    // real public interface object) avoids IllegalAccessException.
    Class<?> iface = findInterface(instance.get().getClass(), request.interfaceName());
    Method method = iface.getMethod(request.methodName(), paramTypes);
    Object[] args = (Object[]) ObjectMarshalling.deserialize(request.serializedArgs());
    return method.invoke(instance.get(), args);
  }

  private static Class<?> findInterface(Class<?> instanceClass, String interfaceName) {
    for (Class<?> candidate : instanceClass.getInterfaces()) {
      if (candidate.getName().equals(interfaceName)) {
        return candidate;
      }
    }
    throw new NoSuchElementException(interfaceName + " is not implemented by " + instanceClass);
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
    for (Closeable listener : listeners) {
      try {
        listener.close();
      } catch (IOException e) {
        log.warn("failed to close fabric listener: {}", e.getMessage());
      }
    }
  }
}
