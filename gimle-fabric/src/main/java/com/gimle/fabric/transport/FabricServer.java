package com.gimle.fabric.transport;

import com.gimle.core.module.ModuleId;
import com.gimle.core.tls.SslContexts;
import com.gimle.core.tls.TlsSettings;
import com.gimle.core.tls.TransportProtocol;
import com.gimle.fabric.trace.TraceContext;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ServiceRegistry;
import com.gimle.observability.WorkerMetrics;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.TraceStateBuilder;
import io.opentelemetry.context.Context;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.StandardProtocolFamily;
import java.net.StandardSocketOptions;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Duration;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.BiConsumer;
import java.util.function.Function;
import javax.net.ssl.SSLServerSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The receiving side of a cross-hop service call: accepts already-connected byte channels -- one
 * {@code ServerSocketChannel} bound to a {@link UnixDomainSocketAddress} for the same-machine tier
 * (always plaintext -- kernel-mediated, never leaves the machine), another bound to a TCP {@link
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
  private final Function<ModuleId, Optional<ModuleContext>> contextLookup;
  private final Function<ModuleId, Optional<ModuleWorkExecutor>> executorLookup;
  private final Optional<WorkerMetrics> metrics;
  private final List<Closeable> listeners = new CopyOnWriteArrayList<>();
  private volatile boolean closed;

  public FabricServer(ServiceRegistry localRegistry, ClassLoader interfaceLoader) {
    this(
        localRegistry,
        interfaceLoader,
        id -> Optional.empty(),
        id -> Optional.empty(),
        Optional.empty());
  }

  /**
   * {@code contextLookup}/{@code executorLookup} let a real worker route an inbound call's actual
   * invocation through the target module's own {@link ModuleContext} in-flight counter (so {@code
   * ModuleController#stop}'s drain wait sees real traffic, not just a hosted module's own hook
   * code) and its concurrency-bounding scheduler (so a slow/hostile caller can't run more
   * concurrent invocations against one module than its own worker allows) -- both absent (the
   * default, single-arg constructor above) for a test or a module this worker isn't itself
   * supervising, in which case a call is still served, just without either safeguard. {@code
   * metrics} absent has the same effect: real request latency/count/error recording, or none.
   */
  public FabricServer(
      ServiceRegistry localRegistry,
      ClassLoader interfaceLoader,
      Function<ModuleId, Optional<ModuleContext>> contextLookup,
      Function<ModuleId, Optional<ModuleWorkExecutor>> executorLookup) {
    this(localRegistry, interfaceLoader, contextLookup, executorLookup, Optional.empty());
  }

  public FabricServer(
      ServiceRegistry localRegistry,
      ClassLoader interfaceLoader,
      Function<ModuleId, Optional<ModuleContext>> contextLookup,
      Function<ModuleId, Optional<ModuleWorkExecutor>> executorLookup,
      Optional<WorkerMetrics> metrics) {
    this.localRegistry = localRegistry;
    this.interfaceLoader = interfaceLoader;
    this.contextLookup = contextLookup;
    this.executorLookup = executorLookup;
    this.metrics = metrics;
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

  /**
   * Rotation hot-swap: closes and rebinds every currently open TLS listener at the same address,
   * picking up whatever certificate material now sits at {@code gimle.tls.certFile}/ {@code
   * keyFile} via {@link #listenTls}, which already reads {@link TlsSettings#fromConfig()} fresh.
   * Never touches the always-plaintext Unix-domain-socket listener -- distinguishable in {@link
   * #listeners} by {@code instanceof SSLServerSocket}, since a UDS {@link ServerSocketChannel}
   * never is one. New connection attempts during the brief close-to-rebind window fail and should
   * be retried by the caller; already-established connections hold their own accepted {@code
   * Socket}, independent of the listening {@link SSLServerSocket} being closed and rebound, so they
   * are unaffected. No-op in plaintext mode.
   */
  public synchronized void reloadTlsMaterial() {
    if (TransportProtocol.fromConfig() == TransportProtocol.PLAINTEXT) {
      return;
    }
    for (Closeable listener : List.copyOf(listeners)) {
      if (!(listener instanceof SSLServerSocket sslServerSocket)) {
        continue;
      }
      SocketAddress address = sslServerSocket.getLocalSocketAddress();
      listeners.remove(listener);
      try {
        sslServerSocket.close();
      } catch (IOException e) {
        log.warn("failed to close fabric TLS listener during reload: {}", e.getMessage());
      }
      try {
        listen(address);
      } catch (IOException e) {
        log.error(
            "failed to rebind fabric TLS listener at {} after TLS reload: {}",
            address,
            e.getMessage());
      }
    }
    log.info("reloaded TLS material for fabric server");
  }

  private void acceptChannelLoop(ServerSocketChannel serverChannel) {
    while (!closed && serverChannel.isOpen()) {
      SocketChannel connection;
      try {
        connection = serverChannel.accept();
      } catch (IOException e) {
        if (closed || !serverChannel.isOpen()) {
          log.warn("fabric server accept loop failed: {}", e.getMessage());
          return;
        }
        // Transient failure (e.g. EMFILE under load) with the listener still open -- keep
        // accepting rather than silently killing the endpoint while health probes stay green.
        log.warn("fabric server accept failed, retrying: {}", e.getMessage());
        if (!pauseBeforeRetry()) {
          return;
        }
        continue;
      }
      try {
        if (connection.getLocalAddress() instanceof InetSocketAddress) {
          // TCP only -- a Unix domain socket has no Nagle to disable, and rejects the option.
          connection.setOption(StandardSocketOptions.TCP_NODELAY, true);
        }
      } catch (IOException e) {
        log.warn(
            "failed to configure accepted fabric connection, dropping it: {}", e.getMessage());
        closeQuietly(connection);
        continue;
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
        if (closed || serverSocket.isClosed()) {
          log.warn("fabric server accept loop failed: {}", e.getMessage());
          return;
        }
        // Transient failure (e.g. EMFILE under load) with the listener still open -- keep
        // accepting rather than silently killing the endpoint while health probes stay green.
        log.warn("fabric server accept failed, retrying: {}", e.getMessage());
        if (!pauseBeforeRetry()) {
          return;
        }
        continue;
      }
      try {
        connection.setTcpNoDelay(true); // see FabricClient: the response half of the round trip
      } catch (IOException e) {
        log.warn(
            "failed to configure accepted fabric connection, dropping it: {}", e.getMessage());
        closeQuietly(connection);
        continue;
      }
      Thread.ofVirtual().name("gimle-fabric-connection").start(() -> serve(connection));
    }
  }

  /**
   * Brief pause between accept retries so a persistent failure (e.g. exhausted file descriptors)
   * doesn't hot-spin the listener thread. Returns {@code false} if interrupted, signalling the
   * caller to stop looping.
   */
  private static boolean pauseBeforeRetry() {
    try {
      Thread.sleep(100);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static void closeQuietly(Closeable connection) {
    try {
      connection.close();
    } catch (IOException e) {
      log.debug("failed to close rejected fabric connection: {}", e.getMessage());
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
    Context spanContext = startChildSpanContext(request);
    Span span = Span.fromContext(spanContext);
    try (var scope = spanContext.makeCurrent()) {
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
    // provider's instance (and its owning module) keyed by the exact Class its own module
    // registered it under -- no separate resolution needed.
    ServiceRegistry.OwnedInstance owned =
        localRegistry
            .lookupOwnedByInterfaceName(request.interfaceName())
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "no local service registered for " + request.interfaceName()));

    Class<?>[] paramTypes =
        ReflectiveDispatch.resolveParamTypes(request.paramTypeNames(), interfaceLoader);
    // The Method must come from the public interface Class, not owned.instance().getClass()
    // directly -- see ReflectiveDispatch.findInterface's own javadoc for why.
    Class<?> iface =
        ReflectiveDispatch.findInterface(owned.instance().getClass(), request.interfaceName());
    Method method = iface.getMethod(request.methodName(), paramTypes);
    Object[] args = (Object[]) ObjectMarshalling.deserialize(request.serializedArgs());

    ModuleId owner = owned.owner();
    Optional<ModuleContext> ctx = contextLookup.apply(owner);
    ctx.ifPresent(ModuleContext::beginRequest);
    long startNanos = System.nanoTime();
    boolean error = false;
    try {
      Optional<ModuleWorkExecutor> executor = executorLookup.apply(owner);
      if (executor.isEmpty()) {
        // No scheduler registered for this module -- a test double, or a module this worker
        // isn't itself supervising via WorkerRuntime. Serve the call directly rather than
        // failing it outright, same "degrade, don't fail" posture the rest of this codebase uses
        // when optional instrumentation isn't wired up.
        return method.invoke(owned.instance(), args);
      }
      return invokeBounded(executor.get(), method, owned.instance(), args);
    } catch (ReflectiveOperationException | RuntimeException e) {
      error = true;
      throw e;
    } finally {
      ctx.ifPresent(ModuleContext::endRequest);
      long elapsedNanos = System.nanoTime() - startNanos;
      boolean recordedError = error;
      metrics.ifPresent(m -> m.recordRequest(owner, Duration.ofNanos(elapsedNanos), recordedError));
    }
  }

  /**
   * Runs {@code method.invoke} on {@code executor} (bounding this module's real concurrent inbound
   * traffic, not just its own probe checks) and blocks for the result, unwrapping {@link
   * ExecutionException} back to exactly the exception {@code method.invoke} would have thrown
   * directly -- {@link #dispatch}'s catch blocks distinguish a genuine {@link
   * InvocationTargetException} (the callee's own exception) from a framework-level failure, and
   * that distinction has to survive crossing the executor's {@link Future} boundary unchanged.
   */
  private Object invokeBounded(
      ModuleWorkExecutor executor, Method method, Object instance, Object[] args)
      throws ReflectiveOperationException {
    Future<Object> future = executor.submit(() -> method.invoke(instance, args));
    try {
      return future.get();
    } catch (ExecutionException e) {
      switch (e.getCause()) {
        case InvocationTargetException ite -> throw ite;
        case IllegalAccessException iae -> throw iae;
        case RuntimeException re -> throw re;
        case null, default ->
            throw new IllegalStateException(
                "unexpected failure invoking via bounded scheduler", e.getCause());
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while invoking via bounded scheduler", e);
    }
  }

  /**
   * Builds the {@link Context} that becomes current for the duration of one dispatched call -- the
   * new child span layered on top of a base context that already carries the caller's {@code
   * baggage}, so {@code invokeLocally}'s handler sees both {@link Span#current()} and {@link
   * Baggage#current()} exactly as the caller had them, not just the span.
   */
  private Context startChildSpanContext(FabricFrame.InvokeRequest request) {
    var trace = request.trace();
    // hasRemoteSpan(trace) == false is FabricServiceRegistry#captureTrace's own "no active span
    // at call time" marker (all-zero trace/span IDs, the W3C spec's own reserved invalid values) --
    // skip ever constructing a SpanContext for it at all, rather than building one from literal
    // zeros and relying on OTel's own isValid()-gated invalid-parent handling (which does happen
    // to treat it as "start a fresh root," but only as an implementation detail of setParent, not
    // a documented contract this code should lean on for correctness).
    Context baseContext = decodeBaggage(trace.baggage()).storeInContext(Context.root());
    Context parentContext =
        hasRemoteSpan(trace) ? baseContext.with(Span.wrap(remoteSpanContext(trace))) : baseContext;
    Span span =
        GlobalOpenTelemetry.getTracer("com.gimle.fabric")
            .spanBuilder(request.interfaceName() + "#" + request.methodName())
            .setSpanKind(SpanKind.SERVER)
            .setParent(parentContext)
            .startSpan();
    return parentContext.with(span);
  }

  // Package-visible for FabricServerTest's direct unit-level assertion, alongside
  // startChildSpanContext's own end-to-end test against a real installed SDK.
  static boolean hasRemoteSpan(TraceContext trace) {
    return trace.traceIdHigh() != 0 || trace.traceIdLow() != 0 || trace.spanId() != 0;
  }

  private static SpanContext remoteSpanContext(TraceContext trace) {
    return SpanContext.createFromRemoteParent(
        traceIdHex(trace.traceIdHigh(), trace.traceIdLow()),
        spanIdHex(trace.spanId()),
        (trace.flags() & 1) != 0 ? TraceFlags.getSampled() : TraceFlags.getDefault(),
        decodeTraceState(trace.tracestate()));
  }

  /** Inverse of {@code FabricServiceRegistry#encodeTraceState} -- see that method's own javadoc. */
  private static TraceState decodeTraceState(String encoded) {
    if (encoded.isEmpty()) {
      return TraceState.getDefault();
    }
    TraceStateBuilder builder = TraceState.builder();
    parsePairs(encoded, builder::put);
    return builder.build();
  }

  /** Inverse of {@code FabricServiceRegistry#encodeBaggage} -- see that method's own javadoc. */
  private static Baggage decodeBaggage(String encoded) {
    BaggageBuilder builder = Baggage.builder();
    parsePairs(encoded, builder::put);
    return builder.build();
  }

  /**
   * Splits a comma-separated {@code key=value} list, calling {@code put} for each pair. A segment
   * with no {@code =} (or one starting with {@code =}) is skipped.
   */
  private static void parsePairs(String encoded, BiConsumer<String, String> put) {
    if (encoded.isEmpty()) {
      return;
    }
    for (String pair : encoded.split(",")) {
      int eq = pair.indexOf('=');
      if (eq > 0) {
        put.accept(pair.substring(0, eq), pair.substring(eq + 1));
      }
    }
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
