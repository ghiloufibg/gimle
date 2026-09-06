package com.gimle.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reclaiming a volume is irreversible and addressed by a coordinate an operator types out by hand:
 * node, set, index, tenant. A destroy that matched nothing -- a mistyped node, an already-reclaimed
 * volume, an untenanted coordinate whose only real volumes are tenant-scoped -- used to exit 0,
 * exactly like a destroy that really removed data, with only the printed sentence to tell them
 * apart. A script, which reads the exit status and nothing else, could not.
 */
class VolumeDestroyExitCodeTest {

  private HttpServer controlPlane;
  private final AtomicInteger destroyStatus = new AtomicInteger(200);
  private ByteArrayOutputStream outBuffer;
  private ByteArrayOutputStream errBuffer;
  private PrintStream out;
  private PrintStream err;
  private String serverAddress;

  @BeforeEach
  void startStubControlPlane() throws IOException {
    controlPlane = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    controlPlane.createContext("/volumes/", this::serveDestroy);
    controlPlane.start();
    serverAddress = "127.0.0.1:" + controlPlane.getAddress().getPort();
    outBuffer = new ByteArrayOutputStream();
    errBuffer = new ByteArrayOutputStream();
    out = new PrintStream(outBuffer, true, StandardCharsets.UTF_8);
    err = new PrintStream(errBuffer, true, StandardCharsets.UTF_8);
  }

  @AfterEach
  void stopStubControlPlane() {
    controlPlane.stop(0);
  }

  /** Answers a destroy the way the control plane does: verbatim relay of the owning node's own. */
  private void serveDestroy(HttpExchange exchange) throws IOException {
    int status = destroyStatus.get();
    byte[] body =
        (status == 200
                ? "{\"destroyed\":true}"
                : "no volume to destroy: orders[0] in the untenanted namespace")
            .getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private int destroy(String... extraArgs) {
    String[] args = new String[6 + extraArgs.length];
    args[0] = "volume";
    args[1] = "destroy";
    args[2] = "orders";
    args[3] = "0";
    args[4] = "--node";
    args[5] = "node-a";
    System.arraycopy(extraArgs, 0, args, 6, extraArgs.length);
    String[] withServer = new String[args.length + 2];
    System.arraycopy(args, 0, withServer, 0, args.length);
    withServer[args.length] = "--server";
    withServer[args.length + 1] = serverAddress;
    return GimleCli.run(withServer, out, err);
  }

  @Test
  void a_destroy_that_removed_nothing_exits_non_zero_rather_than_reporting_success() {
    destroyStatus.set(404);

    assertEquals(
        CliExitCode.NOT_FOUND.code(),
        destroy(),
        "a no-op reclaim must be distinguishable from a real one by exit code alone");
    assertTrue(
        errBuffer.toString(StandardCharsets.UTF_8).contains("no volume to destroy"),
        errBuffer.toString(StandardCharsets.UTF_8));
    assertEquals(
        "", outBuffer.toString(StandardCharsets.UTF_8), "nothing was destroyed, so stdout is bare");
  }

  @Test
  void a_destroy_that_really_removed_a_volume_still_exits_zero() {
    destroyStatus.set(200);

    assertEquals(0, destroy(), errBuffer.toString(StandardCharsets.UTF_8));
    assertEquals(
        "destroyed volume orders[0] on node node-a",
        outBuffer.toString(StandardCharsets.UTF_8).strip());
  }

  /** The tenant is part of the volume's address, so a wrong one is a no-op, and says so. */
  @Test
  void a_destroy_naming_a_tenant_with_no_such_volume_exits_non_zero_too() {
    destroyStatus.set(404);

    assertEquals(CliExitCode.NOT_FOUND.code(), destroy("--tenant", "globex"));
    assertTrue(
        errBuffer.toString(StandardCharsets.UTF_8).contains("for tenant globex"),
        errBuffer.toString(StandardCharsets.UTF_8));
  }
}
