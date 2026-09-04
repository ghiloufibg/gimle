package com.gimle.smoketests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.gimle.core.protocol.Json;
import com.gimle.skald.dns.DnsCodec;
import com.gimle.testkit.Await;
import com.gimle.testkit.PortLease;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real end-to-end coverage of the Service/Bifrost/Skald network path: a real {@code POST /services}
 * fronting a real deployed {@code greeter-provider}, a real {@code gimle-bifrost} node proxy (a
 * second real {@code AgentMain} flag, {@code -Dgimle.agent.bifrostEnabled=true}), and a real {@code
 * SkaldMain} UDP DNS responder answering a real, hand-crafted query for the Service's own name --
 * plus, separately, the already-shipped {@code ServiceExport} tenant re-check proven against a real
 * cross-tenant fabric call.
 *
 * <p>{@code greeter-provider} now opens a real HTTP status listener and reports it via {@code
 * ctx.reportPort} ({@code GreeterProviderHooks}), so with no {@code targetPort} declared here,
 * {@code ServiceEndpointResolver#resolve} resolves the instance's one reported port as the
 * Service's live endpoint -- a plain hosted module (never a Vessel) can now genuinely back a
 * Service, and Skald's own real poll of {@code GET /services/{name}/endpoints} (via {@code
 * HttpServiceCatalogClient}, which decodes the same JSON shape correctly) resolves it into a real
 * DNS answer. One real, structural gap remains and is still documented rather than papered over:
 * {@code HttpServiceSource#listServiceNames} (the class {@code BifrostProxy} polls through, a
 * different client than Skald's) casts each {@code GET /services} array entry straight to {@code
 * String}, but {@code ApiServer#handleServicesList}'s own real response is an array of Service
 * objects ({@code serviceToJson}) -- every real poll throws a {@code ClassCastException}, so {@code
 * BifrostProxy} can never bind a listener at all today, for any Service. Both {@code
 * HttpServiceSource} (gimle-agent) and {@code ApiServer} (its wire counterpart in
 * gimle-controlplane) are outside this module's own scope to fix.
 *
 * <p>Given that, this class's first scenario proves what is genuinely true today: the Service is
 * created for real through the real API, resolves a real live endpoint at greeter-provider's own
 * reported port, and a real hand-crafted DNS query against a real {@code SkaldMain} answers it with
 * a real address -- while the real {@code bifrostEnabled} flag still starts a real {@code
 * BifrostProxy} that can never successfully poll, per the one remaining gap above.
 */
@Tag("smoke")
class ServiceNetworkIT extends GreeterSmokeClusterSupport {

  private static final String SERVICE_NAME = "greeter-net-svc";

  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_declared_service_starts_a_real_bifrost_proxy_and_skald_answers_it_correctly()
      throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    // bifrostEnabledOnAgent=true: the real, no-further-work flag an operator sets to run
    // gimle-bifrost on a node, applied to this fixture's own default agent (smoke-node-1).
    SmokeCluster cluster =
        startCluster(repoRoot, javaExecutable, classpath, /* bifrostEnabledOnAgent= */ true);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path providerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-provider/target/greeter-provider-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(providerJar), "expected a built jar at " + providerJar);

    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        providerJar,
        Duration.ofSeconds(30));
    Await.until(
        () -> isActive(baseUrl, "greeter-provider-deployment"),
        Duration.ofSeconds(60),
        "greeter-provider-deployment should reach ACTIVE");

    int servicePort;
    try (PortLease serviceLease = PortLease.reserve(1)) {
      servicePort = serviceLease.ports().get(0);
      serviceLease.release(servicePort);
    }
    putService(
        baseUrl,
        SERVICE_NAME,
        Optional.empty(),
        Set.of("greeter-provider-deployment"),
        servicePort);

    // Step 3: the Service is real and queryable through the real API (200, correct name/port, no
    // targetPort echoed back since none was declared), and now genuinely resolves a live endpoint
    // -- greeter-provider reports exactly one port (see GreeterProviderHooks), so with no
    // targetPort declared, ServiceEndpointResolver#resolve's own "exactly one reported port"
    // fallback picks it up. This is the fix this scenario now proves rather than a gap it
    // documents: a plain hosted module (never a Vessel) can back a real, dialable Service.
    Await.until(
        () -> serviceIsRegistered(baseUrl, SERVICE_NAME),
        Duration.ofSeconds(30),
        "the Service should be registered and answer GET /services/" + SERVICE_NAME + "/endpoints");
    Map<String, Object> endpointsBody = fetchServiceEndpoints(baseUrl, SERVICE_NAME);
    assertEquals(servicePort, ((Number) endpointsBody.get("port")).intValue());
    assertFalse(endpointsBody.containsKey("targetPort"));
    Await.until(
        () ->
            !Json.asArray(fetchServiceEndpoints(baseUrl, SERVICE_NAME).get("endpoints")).isEmpty(),
        Duration.ofSeconds(30),
        "the Service should resolve a live endpoint now that greeter-provider-deployment reports"
            + " its own listening port");
    List<Map<String, Object>> resolvedEndpoints =
        Json.asObjectList(fetchServiceEndpoints(baseUrl, SERVICE_NAME).get("endpoints"));
    assertEquals(1, resolvedEndpoints.size());
    int reportedPort = ((Number) resolvedEndpoints.get(0).get("port")).intValue();
    assertTrue(
        reportedPort > 0 && reportedPort <= 65535,
        "the resolved endpoint's port should be greeter-provider's own real ephemeral listening"
            + " port, got "
            + reportedPort);
    assertConditionHoldsThroughout(
        () -> {
          List<Map<String, Object>> current =
              Json.asObjectList(fetchServiceEndpoints(baseUrl, SERVICE_NAME).get("endpoints"));
          return current.size() == 1
              && ((Number) current.get(0).get("port")).intValue() == reportedPort;
        },
        Duration.ofSeconds(3),
        Duration.ofSeconds(1),
        "the resolved endpoint should stay stable at the same real port greeter-provider reported");

    // Step 4, scoped: real Bifrost startup, not full reachability. -Dgimle.agent.bifrostEnabled=
    // true genuinely starts a real BifrostProxy against the real control plane (observed here
    // via the agent's own platform log), but its own poll can never succeed against this
    // cluster's real GET /services response today: HttpServiceSource#listServiceNames casts each
    // array entry straight to String, while ApiServer#handleServicesList's own response is an
    // array of Service objects (serviceToJson), not plain name strings -- a real, deterministic
    // (every poll, not a race) cross-module wire-format mismatch this end-to-end run surfaced,
    // confirmed live via this agent's own platform log (BifrostProxy#pollSafely logging a
    // ClassCastException on every tick). Fixing that mismatch is outside gimle-agent, which this
    // module must not touch -- so this asserts what is genuinely true today (the real flag starts
    // a real proxy that is really trying) rather than a loopback Socket connect, which cannot
    // succeed while that mismatch stands.
    InetAddress clusterIp = computeBifrostClusterIp(SERVICE_NAME);
    assertTrue(
        clusterIp.getHostAddress().startsWith("127."),
        "the synthesized ClusterIP bifrost would bind for "
            + SERVICE_NAME
            + " is a loopback address");
    Await.until(
        () -> agentPlatformLogContains(baseUrl, "smoke-node-1", "started bifrost service proxy"),
        Duration.ofSeconds(30),
        "the real -Dgimle.agent.bifrostEnabled=true flag should have started a real BifrostProxy"
            + " on this agent");

    // Step 5: real Skald DNS resolution. The Service now has a real live endpoint (Step 3 above),
    // and ControlPlaneServicePoller reaches it through HttpServiceCatalogClient -- a different
    // client than BifrostProxy's own broken HttpServiceSource, decoding the same
    // GET /services/{name}/endpoints response shape correctly -- so the real, correct answer is
    // now a genuine NOERROR with the resolved address, proving the whole real wire path (real UDP
    // query -> real SkaldServer -> real control-plane poll -> real live endpoint) rather than the
    // "correctly reports nothing exists yet" case this scenario proved before the fix.
    int dnsPort;
    try (PortLease dnsLease = PortLease.reserve(1)) {
      dnsPort = dnsLease.ports().get(0);
      dnsLease.release(dnsPort);
    }
    Process skald =
        spawnSkald(
            javaExecutable,
            classpath,
            dnsPort,
            baseUrl.replaceFirst("^https?://", ""),
            tempDir.resolve("skald.log"));
    processes.add(skald);

    // SkaldMain polls the control plane on its own fixed interval, independent of when this
    // Service's endpoint actually resolved above, so the first query or two may still see a stale
    // NXDOMAIN -- retried until a real answer lands or the overall budget elapses.
    byte[] response =
        awaitDnsAnswer(dnsPort, SERVICE_NAME + ".svc.gimle.local", Duration.ofSeconds(30));
    int rcode = unsignedShort(response, 2) & 0xF;
    int answerCount = unsignedShort(response, 6);
    assertEquals(
        DnsCodec.RCODE_NOERROR,
        rcode,
        "skald should now resolve a real answer for a Service with a live endpoint");
    assertEquals(1, answerCount);
  }

  /**
   * Repeatedly issues the same DNS query (see {@link #queryOverUdp}) until the response answers
   * {@code NOERROR} with at least one record, or {@code overallTimeout} elapses -- needed because
   * {@code SkaldMain}'s own poll cycle against the control plane runs on a fixed interval
   * independent of when the queried Service's endpoint actually resolved.
   */
  private static byte[] awaitDnsAnswer(int dnsPort, String dottedName, Duration overallTimeout)
      throws IOException {
    long deadlineNanos = System.nanoTime() + overallTimeout.toNanos();
    byte[] last;
    do {
      last = queryOverUdp(dnsPort, dottedName, Duration.ofSeconds(5));
      if ((unsignedShort(last, 2) & 0xF) == DnsCodec.RCODE_NOERROR && unsignedShort(last, 6) > 0) {
        return last;
      }
      try {
        Thread.sleep(500);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while awaiting a real skald DNS answer", e);
      }
    } while (System.nanoTime() < deadlineNanos);
    throw new AssertionError(
        "skald never answered NOERROR with a live record for "
            + dottedName
            + " within "
            + overallTimeout);
  }

  /** Reimplements {@code LoopbackAddressAllocator#allocate}'s own hash-derived address exactly. */
  private static InetAddress computeBifrostClusterIp(String serviceName) {
    int unsignedHash = serviceName.hashCode() & 0x7fffffff;
    int x = 1 + (unsignedHash % 254);
    int y = 1 + ((unsignedHash / 254) % 254);
    try {
      return InetAddress.getByAddress(new byte[] {127, (byte) x, (byte) y, 1});
    } catch (UnknownHostException e) {
      // getByAddress only ever validates the array length (4 or 16 bytes); a literal 4-byte
      // array can never fail that check, so this branch is unreachable.
      throw new IllegalStateException(e);
    }
  }

  /**
   * Hand-crafted DNS query bytes -- the same header/question layout {@code gimle-skald}'s own
   * {@code DnsCodecTest#buildQuery} uses, since {@code DnsCodec} itself only ever decodes a query
   * and encodes a response, never the reverse (this server has no client role of its own to need
   * it).
   */
  private static byte[] buildDnsQuery(int id, String dottedName) throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    out.writeShort(id);
    out.writeShort((DnsCodec.OPCODE_QUERY & 0xF) << 11); // RD=0: this server never recurses
    out.writeShort(1); // QDCOUNT
    out.writeShort(0); // ANCOUNT
    out.writeShort(0); // NSCOUNT
    out.writeShort(0); // ARCOUNT
    for (String label : dottedName.split("\\.")) {
      byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
      out.writeByte(bytes.length);
      out.write(bytes);
    }
    out.writeByte(0);
    out.writeShort(DnsCodec.TYPE_A);
    out.writeShort(DnsCodec.CLASS_IN);
    return buffer.toByteArray();
  }

  /**
   * Sends {@code buildDnsQuery}'s own bytes to a real {@code SkaldServer} over loopback UDP,
   * resending on every 1s client-side read timeout until a reply arrives or {@code overallTimeout}
   * elapses -- covers both "skald hasn't finished binding its socket yet" (a real startup race,
   * same shape {@link #awaitPortOpen} handles for a TCP listener) and UDP's own
   * no-delivery-guarantee.
   */
  private static byte[] queryOverUdp(int dnsPort, String dottedName, Duration overallTimeout)
      throws IOException {
    byte[] query = buildDnsQuery(0x51A1, dottedName);
    long deadlineNanos = System.nanoTime() + overallTimeout.toNanos();
    try (DatagramSocket socket = new DatagramSocket()) {
      socket.setSoTimeout(1000);
      DatagramPacket request =
          new DatagramPacket(query, query.length, InetAddress.getLoopbackAddress(), dnsPort);
      byte[] buffer = new byte[512];
      while (System.nanoTime() < deadlineNanos) {
        socket.send(request);
        DatagramPacket responsePacket = new DatagramPacket(buffer, buffer.length);
        try {
          socket.receive(responsePacket);
          return java.util.Arrays.copyOf(responsePacket.getData(), responsePacket.getLength());
        } catch (SocketTimeoutException e) {
          // Retry: skald may not be listening yet, or the datagram was simply lost.
        }
      }
    }
    throw new AssertionError("no DNS response received from skald within " + overallTimeout);
  }

  private static int unsignedShort(byte[] data, int offset) {
    return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
  }

  /**
   * Separately from the Service/Bifrost/Skald path above: the already-shipped fabric listener-side
   * {@code ServiceExport} tenant check, proven end to end against a real cross-tenant call. {@code
   * greeter-provider}'s own committed manifest declares no {@code allowedTenants} at all
   * (unrestricted), so this deploys a real variant that does (built via {@link
   * GreeterSmokeClusterSupport#buildTenantRestrictedProviderJar}), tenant-scoped to {@link
   * GreeterSmokeClusterSupport#SECRET_TENANT_ID} (reusing {@link
   * GreeterSmokeClusterSupport#provisionTenantAndSecret} for its real tenant-registration side
   * effect, mirroring {@code GreeterClusterTopologyIT}), then deploys the real, committed {@code
   * greeter-consumer} left untenanted and asserts its real fabric call is denied.
   *
   * <p>"Left untenanted" in the manifest, not a second named tenant: since every manifest that
   * omits {@code tenantId} now resolves to {@link com.gimle.core.tenant.Tenant#DEFAULT_TENANT_ID}
   * at parse time (the {@code default} namespace equivalent -- see {@code
   * ManifestFields#parseTenantId}'s own javadoc), the consumer's real caller tenant is {@code
   * "default"}, an ordinary named tenant like any other -- {@code ServiceExport#permitsTenant} is
   * an ordinary allow-list membership check here, not the unconditional-deny special case an empty
   * {@code Optional} used to force. The denial this proves is simply that {@code "default"} is not
   * in the provider's {@code allowedTenantIds}, the same as any other non-member tenant would get.
   * Any two tenants can freely co-reside on this fixture's single-node cluster by default -- {@code
   * NodeCandidate}'s own {@code taints} only excludes a tenant an operator has explicitly reserved
   * a node against via {@code StateStore#putNodeTaint}, which this scenario never does, so the
   * consumer's {@code default}-tenant deployment coexists with the tenant-scoped provider on the
   * one node without needing a second agent.
   *
   * <p>{@code ServiceExport#permitsTenant} is checked in two places: client-side, in {@code
   * FabricServiceRegistry#lookup} (filters a restricted candidate out before ever dialing it), and
   * server-side, in {@code FabricServer}'s own inbound dispatch (independent defense-in-depth
   * against a caller that already got past the client-side filter). A genuine deployed consumer
   * always goes through {@code FabricServiceRegistry#lookup} first, so an unauthorized caller's
   * call is denied at the client-side filter before ever reaching the wire -- this is what a real
   * end-to-end run of this scenario actually proves. Exercising {@code FabricServer}'s own
   * server-side re-check specifically would need a caller that bypasses that client-side filter (a
   * raw wire client, the shape {@code FabricServerTest} already covers in-JVM) rather than a
   * genuine deployed module, which is out of scope here -- {@code NetworkPolicySpec} is untouched
   * either way, since it has no enforcement wired yet.
   */
  @Test
  @Timeout(value = 6, unit = TimeUnit.MINUTES)
  void a_cross_tenant_fabric_call_is_denied_by_the_shipped_service_export_check() throws Exception {
    Path repoRoot = repoRoot();
    String javaExecutable = javaExecutable();
    String classpath = System.getProperty("java.class.path");

    SmokeCluster cluster = startCluster(repoRoot, javaExecutable, classpath);
    String baseUrl = cluster.controlPlaneBaseUrls().get(0);

    Path consumerJar =
        repoRoot.resolve(
            "gimle-examples/greeter-consumer/target/greeter-consumer-" + GIMLE_VERSION + ".jar");
    assertTrue(Files.isRegularFile(consumerJar), "expected a built jar at " + consumerJar);

    provisionTenantAndSecret(baseUrl);
    Path restrictedProviderJar = buildTenantRestrictedProviderJar(SECRET_TENANT_ID);

    submitDeploymentWithRetry(
        baseUrl,
        "greeter-provider-deployment",
        "com.gimle.examples.greeter.provider",
        restrictedProviderJar,
        Optional.of(SECRET_TENANT_ID),
        Duration.ofSeconds(30));
    submitDeploymentWithRetry(
        baseUrl,
        "greeter-consumer-deployment",
        "com.gimle.examples.greeter.consumer",
        consumerJar,
        Duration.ofSeconds(30));

    Await.until(
        () -> isActive(baseUrl, "greeter-provider-deployment", Optional.of(SECRET_TENANT_ID)),
        Duration.ofSeconds(60),
        "the tenant-restricted greeter-provider-deployment should reach ACTIVE (starting fine is"
            + " the point -- only a cross-tenant call into it is denied)");
    Await.until(
        () -> isActive(baseUrl, "greeter-consumer-deployment"),
        Duration.ofSeconds(60),
        "greeter-consumer-deployment should reach ACTIVE");

    // The real denial, observed purely through the consumer's own application log: its own
    // lookup keeps coming back empty (ServiceExport#permitsTenant excludes the restricted
    // candidate, see this method's own javadoc) rather than ever landing a real call.
    Await.until(
        () ->
            instanceLogContains(
                baseUrl,
                "greeter-consumer-deployment",
                0,
                "APPLICATION",
                "no Greeter available yet"),
        Duration.ofSeconds(30),
        "greeter-consumer-deployment's own log should show its real lookup finding no permitted"
            + " Greeter candidate");

    assertConditionHoldsThroughout(
        () -> !consumerLogShowsAGreeting(baseUrl),
        Duration.ofSeconds(20),
        Duration.ofSeconds(1),
        "greeter-consumer-deployment must never show a successful greeting: left untenanted, it"
            + " can never satisfy greeter-provider's restricted allowedTenants, so"
            + " ServiceExport#permitsTenant should keep excluding it from every lookup");
    assertFalse(
        consumerLogShowsAGreeting(baseUrl), "no successful cross-tenant greeting should ever land");
  }
}
