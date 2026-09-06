package com.gimle.hilmir.validate;

import com.gimle.core.exception.GimleManifestException;
import com.gimle.hilmir.HilmirException;
import com.gimle.hilmir.topology.Topology;
import com.gimle.hilmir.topology.TopologyParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A topology document loaded and checked in one step, so that every way it can be rejected -- the
 * file is missing, the YAML is malformed, a field is the wrong shape, or a semantic rule fired --
 * arrives as the same coded {@link Finding}. An operator grepping or scripting against a verb's
 * output should not have to recognize a second, differently-shaped error format just because the
 * document failed before the rule catalog ever ran.
 *
 * <p>{@code topology} is present exactly when nothing prevented the document from being read into a
 * {@link Topology} at all; the semantic findings on a topology that did parse are carried alongside
 * it. The two codes minted here -- {@code UNREADABLE_TOPOLOGY} and {@code MALFORMED_TOPOLOGY} --
 * are part of the same stable, matchable vocabulary {@link TopologyValidator}'s own codes are.
 */
public record CheckedTopology(Optional<Topology> topology, List<Finding> findings) {

  public CheckedTopology {
    findings = List.copyOf(findings);
  }

  public static CheckedTopology check(final Path file) {
    final Topology topology;
    try {
      topology = TopologyParser.parseFile(file);
    } catch (final HilmirException e) {
      return rejected("UNREADABLE_TOPOLOGY", e.getMessage());
    } catch (final GimleManifestException e) {
      return rejected("MALFORMED_TOPOLOGY", e.getMessage());
    }
    return new CheckedTopology(Optional.of(topology), TopologyValidator.validate(topology));
  }

  public boolean hasError() {
    return findings.stream().anyMatch(f -> f.severity() == Severity.ERROR);
  }

  /**
   * The parsed topology, callable only after {@link #hasError()} has been found {@code false} -- a
   * document that failed to load always carries an {@code ERROR} finding, so a caller that checked
   * first can never reach the failure here.
   */
  public Topology require() {
    return topology.orElseThrow(
        () -> new HilmirException("topology document could not be read; see the findings above"));
  }

  private static CheckedTopology rejected(final String code, final String message) {
    return new CheckedTopology(
        Optional.empty(), List.of(new Finding(code, Severity.ERROR, message)));
  }
}
