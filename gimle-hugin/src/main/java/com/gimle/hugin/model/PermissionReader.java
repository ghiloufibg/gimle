package com.gimle.hugin.model;

import com.gimle.cli.spi.ClusterReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Asks the control plane what this caller may do, by putting the question to it once per cell.
 *
 * <p>{@code GET /authz/vocabulary} names the kinds and verbs that exist, and {@code GET
 * /authz/can-i} answers one pair at a time -- there is no route that answers the whole grid, so the
 * grid is one request per cell and the read is deliberately made on demand rather than on a clock.
 *
 * <p>The cells are asked concurrently on virtual threads. Sequentially this would be a couple of
 * hundred round trips in a row, which on a remote cluster is the difference between a screen that
 * appears and one that arrives.
 */
public final class PermissionReader {

  /** Shown where no cell came back to say who the control plane answered as. */
  private static final String UNKNOWN_PRINCIPAL = "\u2014";

  private final ClusterReader reader;
  private final Optional<String> tenantId;

  public PermissionReader(final ClusterReader reader, final Optional<String> tenantId) {
    this.reader = reader;
    this.tenantId = tenantId;
  }

  public PermissionSnapshot read() {
    Map<String, Object> vocabulary;
    try {
      vocabulary = reader.getObject("/authz/vocabulary");
    } catch (RuntimeException e) {
      return PermissionSnapshot.unreadable(reader.serverAddress(), describe(e));
    }
    List<String> kinds = names(vocabulary.get("resourceKinds"));
    List<String> verbs = names(vocabulary.get("verbs"));
    if (kinds.isEmpty() || verbs.isEmpty()) {
      return PermissionSnapshot.unreadable(
          reader.serverAddress(), "the control plane named no kinds or verbs to ask about");
    }
    return grid(kinds, verbs);
  }

  private PermissionSnapshot grid(final List<String> kinds, final List<String> verbs) {
    Map<String, Map<String, Future<Optional<Answer>>>> asked = new LinkedHashMap<>();
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (String kind : kinds) {
        Map<String, Future<Optional<Answer>>> row = new LinkedHashMap<>();
        for (String verb : verbs) {
          row.put(verb, executor.submit(() -> ask(kind, verb)));
        }
        asked.put(kind, row);
      }
    }

    Optional<String> principal = Optional.empty();
    List<PermissionRow> rows = new ArrayList<>();
    for (Map.Entry<String, Map<String, Future<Optional<Answer>>>> entry : asked.entrySet()) {
      Map<String, Boolean> allowed = new LinkedHashMap<>();
      for (Map.Entry<String, Future<Optional<Answer>>> cell : entry.getValue().entrySet()) {
        Optional<Answer> answer = settled(cell.getValue());
        if (answer.isPresent()) {
          allowed.put(cell.getKey(), answer.get().allowed());
          principal = principal.or(() -> Optional.of(answer.get().principal()));
        }
      }
      rows.add(new PermissionRow(entry.getKey(), allowed));
    }
    rows.sort(Comparator.comparing(PermissionRow::kind));
    return new PermissionSnapshot(
        reader.serverAddress(),
        Optional.of(Instant.now()),
        principal.orElse(UNKNOWN_PRINCIPAL),
        tenantId,
        verbs,
        rows,
        true,
        Optional.empty());
  }

  /**
   * One cell. A cell that fails is left unanswered rather than reported as a denial: the two look
   * identical once drawn, and only one of them is a statement about this caller's grants.
   */
  private Optional<Answer> ask(final String kind, final String verb) {
    try {
      Map<String, Object> body = reader.getObject(path(kind, verb));
      if (!(body.get("allowed") instanceof Boolean allowed)) {
        // A body without a verdict in it is not a denial. Reading one as "no" would put a refusal
        // on the screen that nothing ever said.
        return Optional.empty();
      }
      return Optional.of(
          new Answer(
              allowed,
              body.get("principal") instanceof String name && !name.isBlank()
                  ? name
                  : UNKNOWN_PRINCIPAL));
    } catch (RuntimeException e) {
      return Optional.empty();
    }
  }

  String path(final String kind, final String verb) {
    StringBuilder path =
        new StringBuilder("/authz/can-i?resource=")
            .append(encode(kind))
            .append("&verb=")
            .append(encode(verb));
    tenantId.ifPresent(tenant -> path.append("&tenant=").append(encode(tenant)));
    return path.toString();
  }

  /**
   * The answer only if the task actually finished with one. The executor's own {@code close} waits
   * for every task, so this is normally always the case -- but a thread interrupted while waiting
   * leaves cells unfinished, and those must read as unanswered rather than throwing away the whole
   * grid or being counted as denials.
   */
  private static Optional<Answer> settled(final Future<Optional<Answer>> cell) {
    return cell.state() == Future.State.SUCCESS ? cell.resultNow() : Optional.empty();
  }

  private static List<String> names(final Object value) {
    if (!(value instanceof List<?> list)) {
      return List.of();
    }
    List<String> names = new ArrayList<>();
    for (Object item : list) {
      if (item instanceof String name && !name.isBlank()) {
        names.add(name);
      }
    }
    return names;
  }

  private static String encode(final String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String describe(final RuntimeException e) {
    String message = e.getMessage();
    return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
  }

  /** One cell's answer, and the identity the control plane answered it for. */
  private record Answer(boolean allowed, String principal) {}
}
