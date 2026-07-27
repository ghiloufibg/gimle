package com.gimle.fabric.catalog;

import com.gimle.core.module.ModuleId;
import com.gimle.core.module.ServiceExport;
import com.gimle.core.module.Version;
import com.gimle.fabric.cluster.MemberId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Encodes/decodes a bounded {@code List<CatalogDelta>} to the opaque {@code byte[]} payload {@link
 * ServiceCatalog} hands {@code GossipMember} for its {@code PiggybackExtension} slot (Phase 4 §5).
 * Package-private wire plumbing, same posture as {@code SwimCodec}.
 *
 * <p>{@code allowedTenantIds} (Phase 5 design §5.3) is framed as a length-prefixed field rather
 * than comma-joined text: {@code -1} means {@code Optional.empty()} ("any tenant"), otherwise a
 * non-negative count followed by that many {@code writeUTF} entries -- avoids inventing a
 * delimiter-escaping scheme for a value {@code DataOutputStream} already frames robustly.
 */
final class ServiceCatalogCodec {

  private ServiceCatalogCodec() {}

  static byte[] encode(List<CatalogDelta> deltas) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(buffer);
    try {
      out.writeInt(deltas.size());
      for (CatalogDelta delta : deltas) {
        out.writeUTF(delta.export().interfaceName());
        out.writeUTF(delta.export().version().toString());
        writeAllowedTenants(out, delta.export().allowedTenantIds());
        out.writeUTF(delta.nodeId());
        out.writeUTF(delta.workerId());
        out.writeUTF(delta.moduleId().name());
        out.writeUTF(delta.moduleId().version().toString());
        out.writeLong(delta.version());
        out.writeBoolean(delta.present());
        out.writeUTF(delta.node().gossipAddress().getHostString());
        out.writeInt(delta.node().gossipAddress().getPort());
        out.writeUTF(delta.udsPath().orElse(""));
        out.writeUTF(delta.tcpAddress().getHostString());
        out.writeInt(delta.tcpAddress().getPort());
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  private static void writeAllowedTenants(
      DataOutputStream out, Optional<Set<String>> allowedTenantIds) throws IOException {
    if (allowedTenantIds.isEmpty()) {
      out.writeInt(-1);
      return;
    }
    Set<String> tenants = allowedTenantIds.get();
    out.writeInt(tenants.size());
    for (String tenant : tenants) {
      out.writeUTF(tenant);
    }
  }

  private static Optional<Set<String>> readAllowedTenants(DataInputStream in) throws IOException {
    int count = in.readInt();
    if (count < 0) {
      return Optional.empty();
    }
    Set<String> tenants = new LinkedHashSet<>();
    for (int i = 0; i < count; i++) {
      tenants.add(in.readUTF());
    }
    return Optional.of(tenants);
  }

  static List<CatalogDelta> decode(byte[] payload) {
    if (payload.length == 0) {
      return List.of();
    }
    try {
      DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
      int count = in.readInt();
      List<CatalogDelta> deltas = new ArrayList<>();
      for (int i = 0; i < count; i++) {
        String interfaceName = in.readUTF();
        Version version = Version.parse(in.readUTF());
        Optional<Set<String>> allowedTenantIds = readAllowedTenants(in);
        ServiceExport export =
            allowedTenantIds.isEmpty()
                ? new ServiceExport(interfaceName, version)
                : new ServiceExport(interfaceName, version, allowedTenantIds);
        String nodeId = in.readUTF();
        String workerId = in.readUTF();
        ModuleId moduleId = new ModuleId(in.readUTF(), Version.parse(in.readUTF()));
        long deltaVersion = in.readLong();
        boolean present = in.readBoolean();
        String gossipHost = in.readUTF();
        int gossipPort = in.readInt();
        MemberId node = new MemberId(nodeId, new InetSocketAddress(gossipHost, gossipPort));
        String udsPath = in.readUTF();
        String tcpHost = in.readUTF();
        int tcpPort = in.readInt();
        deltas.add(
            new CatalogDelta(
                export,
                nodeId,
                workerId,
                moduleId,
                deltaVersion,
                present,
                node,
                udsPath.isEmpty() ? Optional.empty() : Optional.of(udsPath),
                new InetSocketAddress(tcpHost, tcpPort)));
      }
      return deltas;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
