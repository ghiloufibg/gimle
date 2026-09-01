package com.gimle.gateway;

import com.gimle.core.tls.HostCertificate;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Parses the optional {@code gateway.tlsCertificates} config value ({@code
 * ModuleContext#config("gateway.tlsCertificates")}) into the per-hostname certificate bindings a
 * TLS-terminating gateway selects among by SNI. Same shape as {@link GatewayRouteConfig}'s own
 * format deliberately -- one binding per line, blank lines and {@code #} comments ignored:
 *
 * <pre>{@code
 * <hostname> <certFile> <keyFile>
 * }</pre>
 *
 * <p>This exists because {@link GatewayRouteConfig} routes by the inbound {@code Host} header, so a
 * single gateway legitimately fronts several hostnames -- but a single certificate can only satisfy
 * hostname verification for the names in its own SAN, leaving every other routed hostname able to
 * reach the right route only after failing TLS. Each binding names the certificate presented for
 * one hostname; the cluster-wide {@code gimle.tls.certFile}/{@code keyFile} pair stays the
 * certificate presented to a client that sends no SNI or names a hostname with no binding here, so
 * a gateway that configures nothing at all still behaves exactly as a single-certificate listener.
 *
 * <p>No {@code caFile} per binding: trust is cluster-wide and already carried by {@code
 * gimle.tls.caFile}. What varies per virtual host is only which identity the gateway presents.
 *
 * <p>Read once, at {@code onStart} -- unlike {@code gateway.routes}, swapping the certificate an
 * already-established listener presents is not a table swap, so a certificate change stays a
 * redeploy the same way changing {@code gateway.port} does.
 *
 * <p>Example:
 *
 * <pre>{@code
 * # hostname            certFile                        keyFile
 * orders.example.com    /etc/gimle/tls/orders-cert.pem  /etc/gimle/tls/orders-key.pem
 * shop.example.com      /etc/gimle/tls/shop-cert.pem    /etc/gimle/tls/shop-key.pem
 * }</pre>
 */
public final class GatewayTlsConfig {

  private GatewayTlsConfig() {}

  public static List<HostCertificate> parse(String text) {
    List<HostCertificate> certificates = new ArrayList<>();
    Set<String> seenHostnames = new HashSet<>();
    String[] lines = text.split("\n", -1);
    for (int lineNumber = 1; lineNumber <= lines.length; lineNumber++) {
      String line = lines[lineNumber - 1].strip();
      if (line.isEmpty() || line.startsWith("#")) {
        continue;
      }
      HostCertificate certificate = parseLine(line, lineNumber);
      if (!seenHostnames.add(certificate.hostname())) {
        throw new GatewayConfigException(
            "duplicate TLS certificate for host '"
                + certificate.hostname()
                + "' at line "
                + lineNumber);
      }
      certificates.add(certificate);
    }
    return List.copyOf(certificates);
  }

  private static HostCertificate parseLine(String line, int lineNumber) {
    String[] fields = line.split("\\s+");
    if (fields.length != 3) {
      throw new GatewayConfigException(
          "malformed TLS certificate binding at line "
              + lineNumber
              + ": expected 3 fields (hostname certFile keyFile), got "
              + fields.length
              + ": "
              + line);
    }
    String hostname = fields[0].toLowerCase(Locale.ROOT);
    if (hostname.startsWith("*")) {
      throw new GatewayConfigException(
          "malformed TLS certificate binding at line "
              + lineNumber
              + ": wildcard hostnames are not supported, name each host explicitly: "
              + hostname);
    }
    return new HostCertificate(hostname, Path.of(fields[1]), Path.of(fields[2]));
  }
}
