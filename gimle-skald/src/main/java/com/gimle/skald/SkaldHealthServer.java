package com.gimle.skald;

import com.gimle.core.web.HttpResponses;
import com.gimle.skald.directory.ServiceDirectory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * A tiny HTTP surface answering only "is this responder healthy" -- the one thing Skald's own DNS
 * port cannot be asked.
 *
 * <p>It exists because the platform's probe ladder has no UDP rung: {@code VesselProbeSpec} offers
 * TCP and HTTP, both of which a DNS-over-UDP process is invisible to, leaving a Skald workload with
 * nothing above the "process still running" floor. Rather than teach every probe mechanism to speak
 * DNS, Skald exposes what a prober can already reach. CoreDNS carries its own {@code /health} and
 * {@code /ready} endpoints for exactly this reason and exactly this shape.
 *
 * <p>The two answers are deliberately different questions, the same split CoreDNS draws. {@code
 * /health} is liveness: this process is up and its responder is bound, so restarting it would
 * accomplish nothing. {@code /ready} is readiness: the service directory has been refreshed
 * recently enough to answer queries with current data -- a responder whose control-plane polls have
 * been failing is still alive, but answering from a stale directory, and should be taken out of a
 * Service's endpoint set rather than killed. Collapsing the two would make a control-plane outage
 * look like a Skald crash loop and restart every replica for a fault none of them has.
 */
public final class SkaldHealthServer implements AutoCloseable {

  private final HttpServer server;
  private final ServiceDirectory directory;
  private final Duration stalenessThreshold;

  public SkaldHealthServer(ServiceDirectory directory, int port, Duration stalenessThreshold)
      throws IOException {
    this.directory = directory;
    this.stalenessThreshold = stalenessThreshold;
    this.server = HttpServer.create(new InetSocketAddress(port), 0);
    server.createContext("/health", this::handleHealth);
    server.createContext("/ready", this::handleReady);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  private void handleHealth(HttpExchange exchange) throws IOException {
    try {
      HttpResponses.respond(exchange, 200, "ok");
    } finally {
      exchange.close();
    }
  }

  private void handleReady(HttpExchange exchange) throws IOException {
    try {
      boolean fresh = directory.timeSinceLastSuccess().compareTo(stalenessThreshold) <= 0;
      HttpResponses.respond(
          exchange,
          fresh ? 200 : 503,
          fresh
              ? "ready"
              : "directory last refreshed "
                  + directory.timeSinceLastSuccess().toSeconds()
                  + "s ago, past the "
                  + stalenessThreshold.toSeconds()
                  + "s threshold");
    } finally {
      exchange.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
