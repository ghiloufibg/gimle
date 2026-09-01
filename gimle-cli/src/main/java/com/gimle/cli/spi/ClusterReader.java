package com.gimle.cli.spi;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * A read-only view of the control-plane API, handed to a {@link CliExtension} in place of the CLI's
 * own HTTP client. The client's {@code put}/{@code post}/{@code patch}/{@code delete} methods are
 * simply not on this type, so an extension cannot reach a mutation at all -- a compile-time
 * restriction rather than a documented discouragement, and one that holds whichever classloader the
 * extension arrives from.
 *
 * <p>The three methods mirror the client's own read surface exactly: no new HTTP code exists behind
 * this interface, only a narrowing.
 */
public interface ClusterReader {

  /** GETs {@code path}, expecting a JSON array of objects. */
  List<Map<String, Object>> getList(String path);

  /** GETs {@code path}, expecting a JSON object. */
  Map<String, Object> getObject(String path);

  /**
   * Opens {@code path} as a long-lived streaming GET -- a {@code follow=true} log tail -- returning
   * the response body unbuffered for the caller to read as bytes arrive. The caller owns closing
   * it.
   */
  InputStream openStream(String path);

  /** The control-plane address this reader talks to, for display. */
  String serverAddress();
}
