package com.gimle.fabric.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ModuleInstanceId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.cluster.MemberId;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * Observed under this module's own class-level concurrency (root pom.xml) silently under- reporting
 * its test count (0 or 2 of 3 methods executed, no failure raised) rather than failing outright --
 * a worse symptom shape than the timing failures {@code RaftClusterTest} (gimle-mimir) and {@code
 * SecretStoreTest} (gimle-fafnir) already needed {@link Isolated} for, since a build this happens
 * to can still report BUILD SUCCESS. Same CPU-contention-under-class-level- concurrency root cause
 * as those, just manifesting as dropped test execution instead of a missed timeout.
 */
@Isolated
class ServiceCatalogCodecTest {

  private static final MemberId NODE =
      new MemberId("node-1", new InetSocketAddress("127.0.0.1", 7001));

  @Test
  void round_trips_a_catalog_delta() {
    CatalogDelta original =
        new CatalogDelta(
            new ServiceExport("com.gimle.example.Greeter", Version.parse("1.0.0")),
            "node-1",
            "worker-1",
            ModuleInstanceId.unattached(new ModuleId("greeter", Version.parse("1.0.0"))),
            5L,
            true,
            NODE,
            Optional.of("/tmp/greeter.sock"),
            new InetSocketAddress("127.0.0.1", 9001));

    byte[] payload = ServiceCatalogCodec.encode(List.of(original));
    List<CatalogDelta> decoded = ServiceCatalogCodec.decode(payload);

    assertEquals(1, decoded.size());
    assertEquals(original, decoded.get(0));
  }

  @Test
  void round_trips_an_empty_delta_list() {
    List<CatalogDelta> decoded = ServiceCatalogCodec.decode(ServiceCatalogCodec.encode(List.of()));
    assertEquals(List.of(), decoded);
  }

  @Test
  void a_forged_huge_delta_count_fails_cleanly_instead_of_preallocating() throws IOException {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    new DataOutputStream(buffer).writeInt(Integer.MAX_VALUE - 8);
    byte[] payload = buffer.toByteArray();

    assertThrows(UncheckedIOException.class, () -> ServiceCatalogCodec.decode(payload));
  }
}
