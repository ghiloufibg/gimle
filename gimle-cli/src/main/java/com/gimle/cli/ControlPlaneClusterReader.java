package com.gimle.cli;

import com.gimle.cli.spi.ClusterReader;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The one implementation of {@link ClusterReader}: a narrowing view of {@link ControlPlaneClient}
 * that forwards the three read methods and exposes nothing else. Constructing this is the only way
 * an extension ever reaches the control plane, so the write methods stay out of reach without
 * needing a second HTTP client or a permission check of their own.
 */
final class ControlPlaneClusterReader implements ClusterReader {

  /** Host (or bracketed IPv6 literal) and port -- what {@code ControlPlaneClient} can dial. */
  private static final Pattern SERVER =
      Pattern.compile("(\\[[0-9A-Fa-f:.]+]|[A-Za-z0-9._-]+):\\d{1,5}");

  private final ControlPlaneClient client;
  private final String serverAddress;

  ControlPlaneClusterReader(final ControlPlaneClient client, final String serverAddress) {
    this.client = client;
    this.serverAddress = serverAddress;
  }

  @Override
  public List<Map<String, Object>> getList(final String path) {
    return client.getList(path);
  }

  @Override
  public Map<String, Object> getObject(final String path) {
    return client.getObject(path);
  }

  @Override
  public InputStream openStream(final String path) {
    return client.openStream(path);
  }

  @Override
  public String serverAddress() {
    return serverAddress;
  }

  /**
   * A stored context wins over a bare address, so a context deliberately named after a host cannot
   * be shadowed by the host itself. Anything that is neither is refused rather than dialled, since
   * a typo dialled as a hostname fails later and less clearly than it does here.
   */
  @Override
  public ClusterReader forContext(final String nameOrAddress) {
    String target = nameOrAddress == null ? "" : nameOrAddress.trim();
    String server =
        CliConfig.load(CliConfig.defaultPath()).contexts().stream()
            .filter(context -> context.name().equals(target))
            .map(CliContext::server)
            .findFirst()
            .orElse(target);
    if (!SERVER.matcher(server).matches()) {
      throw new CliException(
          "no context named '" + target + "', and it is not a host:port address either");
    }
    return new ControlPlaneClusterReader(new ControlPlaneClient(server), server);
  }
}
