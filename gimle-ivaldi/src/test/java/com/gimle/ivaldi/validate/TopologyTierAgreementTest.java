package com.gimle.ivaldi.validate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import com.gimle.hilmir.validate.TopologyValidator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Proves tier 1 (the console's own {@code lib/rules.ts}, browser-side) and tier 2 (this class's own
 * {@link TopologyValidator}, the real Hilmir validator) genuinely agree on the codes they report
 * for a topology, rather than trusting that the two independently-written rule catalogs happen to
 * match.
 *
 * <p>Both sides read the exact same fixture: {@code src/test/resources/golden/*-topology.yaml} is
 * {@code render.ts}'s own byte-for-byte output for {@code gimle-ivaldi-console}'s two sample
 * Blueprints (the clean {@code orders-platform-local} and the deliberately broken {@code
 * broken-example}), not hand-written YAML -- see {@code
 * gimle-ivaldi-console/src/lib/rules.golden.test.ts}, which reads this very file to assert (a) it
 * still matches {@code renderFiles()}'s current output (the drift guard: regenerate this fixture if
 * that assertion ever fails) and (b) tier 1's {@code validateTopology} reports the exact code set
 * hardcoded below. If a topology rule is ever added to one side and not the other, one of these two
 * tests -- this one or {@code rules.golden.test.ts} -- starts failing.
 *
 * <p>Compared as a distinct code <em>set</em>, not a duplicate-counting list: tier 1 deliberately
 * emits one {@code Problem} per implicated canvas node for a colocation/conflict rule (each carries
 * its own {@code nodeId} so the Designer can highlight every offending node), while {@link
 * TopologyValidator} emits exactly one {@link com.gimle.hilmir.validate.Finding} per situation --
 * it has no per-node concept, only the machine/port/role text a CLI report needs. Two agents on one
 * machine is genuinely one {@code AGENTS_COLOCATED} finding here and two {@code Problem}s there;
 * asserting a matching count would fail on that legitimate difference, not a real disagreement
 * about which rules fired. What both sides must agree on is exactly this: whether the code fired at
 * all.
 *
 * <p>Scoped to topology codes deliberately: tier 1's own application-level codes (quota, placement,
 * service cross-tenant checks, ...) have no tier-2 equivalent by design -- {@link
 * FileSetValidator}'s manifest/service/networkPolicy checks re-verify against the real parsers
 * under a genuinely different code scheme (e.g. {@code SERVICE_INVALID} wrapping whatever {@link
 * com.gimle.mimir.manifest.ServiceSpec}'s own constructor rejects), not a code-for-code mirror the
 * way the topology catalog is. Comparing those would be comparing apples to oranges, not proving
 * agreement.
 */
class TopologyTierAgreementTest {

  private static Topology parseFixture(String resourceName) {
    return TopologyParser.parse(streamOf(resourceName));
  }

  private static InputStream streamOf(String resourceName) {
    try (InputStream in =
        TopologyTierAgreementTest.class.getResourceAsStream("/golden/" + resourceName)) {
      if (in == null) {
        throw new IllegalStateException("missing test fixture: golden/" + resourceName);
      }
      return new ByteArrayInputStream(in.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException("failed reading fixture golden/" + resourceName, e);
    }
  }

  private static Set<String> codesOf(Topology topology) {
    return TopologyValidator.validate(topology).stream()
        .map(com.gimle.hilmir.validate.Finding::code)
        .collect(Collectors.toSet());
  }

  @Test
  void clean_sample_topology_reports_the_same_codes_tier_1_reports() {
    // Cross-referenced with rules.golden.test.ts's own "orders-platform-local" expectation --
    // keep both sets identical if either ever changes.
    Set<String> expected = Set.of("AGENTS_COLOCATED", "SINGLE_STORE", "SINGLE_CONTROL_PLANE");
    assertEquals(expected, codesOf(parseFixture("orders-platform-local-topology.yaml")));
  }

  @Test
  void broken_sample_topology_reports_the_same_codes_tier_1_reports() {
    // Cross-referenced with rules.golden.test.ts's own "broken-example" expectation -- keep both
    // sets identical if either ever changes.
    Set<String> expected =
        Set.of(
            "PORT_CONFLICT",
            "REPLICAS_COLOCATED",
            "MTLS_NO_MATERIAL_DIR",
            "MTLS_IP_LITERAL_HOST",
            "SINGLE_STORE");
    assertEquals(expected, codesOf(parseFixture("broken-example-topology.yaml")));
  }
}
