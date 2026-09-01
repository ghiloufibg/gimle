package com.gimle.cli;

import com.gimle.cli.spi.ClusterReader;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * The one implementation of {@link ClusterReader}: a narrowing view of {@link ControlPlaneClient}
 * that forwards the three read methods and exposes nothing else. Constructing this is the only way
 * an extension ever reaches the control plane, so the write methods stay out of reach without
 * needing a second HTTP client or a permission check of their own.
 */
final class ControlPlaneClusterReader implements ClusterReader {

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
}
