package com.gimle.core.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;

/**
 * Registered at {@code /} alongside {@link SpaStaticHandler} so hitting a bare bundled-console
 * process's root address doesn't dead-end in the JDK {@code HttpServer}'s own bare {@code 404} --
 * it 302s the exact root path to {@code target} (the console's own mount point, e.g. {@code
 * /console}) and leaves everything else to fall through to that same {@code 404}, exactly as it did
 * before this context existed. That "everything else" distinction matters: {@code HttpServer}
 * dispatches by longest-prefix match, so once a handler owns {@code /} it becomes the catch-all for
 * every request no more specific context claims -- blindly redirecting all of those here would
 * quietly turn a genuine unmatched-path {@code 404} into a {@code 302}, masking it.
 */
public final class RootRedirectHandler implements HttpHandler {

  private final String target;

  public RootRedirectHandler(String target) {
    this.target = target;
  }

  @Override
  public void handle(HttpExchange exchange) throws IOException {
    try {
      if (exchange.getRequestURI().getPath().equals("/")) {
        exchange.getResponseHeaders().add("Location", target);
        exchange.sendResponseHeaders(302, -1);
        return;
      }
      exchange.sendResponseHeaders(404, -1);
    } finally {
      exchange.close();
    }
  }
}
