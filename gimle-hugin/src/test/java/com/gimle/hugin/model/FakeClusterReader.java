package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A {@link ClusterReader} whose responses the test writes. Real HTTP is exercised where it belongs
 * -- {@code gimle-cli}'s own suite runs its commands against a real {@code ApiServer} -- so what is
 * worth pinning down here is the parsing and the behaviour around a response, not the transport.
 */
final class FakeClusterReader implements ClusterReader {

  private final Map<String, List<Map<String, Object>>> lists = new ConcurrentHashMap<>();
  private final Map<String, Map<String, Object>> objects = new ConcurrentHashMap<>();
  private final Map<String, String> streams = new ConcurrentHashMap<>();
  // Read from the test thread while the watcher's own two threads are still appending to it.
  private final List<String> requestedPaths = new CopyOnWriteArrayList<>();

  private volatile RuntimeException failure;

  FakeClusterReader withList(final String path, final List<Map<String, Object>> value) {
    lists.put(path, value);
    return this;
  }

  FakeClusterReader withObject(final String path, final Map<String, Object> value) {
    objects.put(path, value);
    return this;
  }

  FakeClusterReader withStream(final String path, final String body) {
    streams.put(path, body);
    return this;
  }

  /** Makes every subsequent call fail, the way an unreachable control plane does. */
  void failWith(final RuntimeException failure) {
    this.failure = failure;
  }

  List<String> requestedPaths() {
    return List.copyOf(requestedPaths);
  }

  @Override
  public List<Map<String, Object>> getList(final String path) {
    record(path);
    return lists.getOrDefault(path, List.of());
  }

  @Override
  public Map<String, Object> getObject(final String path) {
    record(path);
    return objects.getOrDefault(path, Map.of());
  }

  @Override
  public InputStream openStream(final String path) {
    record(path);
    return new ByteArrayInputStream(
        streams.getOrDefault(path, "").getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public String serverAddress() {
    return "localhost:8080";
  }

  private void record(final String path) {
    requestedPaths.add(path);
    if (failure != null) {
      throw failure;
    }
  }
}
