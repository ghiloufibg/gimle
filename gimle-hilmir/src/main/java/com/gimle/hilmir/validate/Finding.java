package com.gimle.hilmir.validate;

import java.util.Optional;

/**
 * One rule outcome from {@link TopologyValidator}. {@code code} is the public, stable contract --
 * it names the rule that fired and is meant to be grepped for, scripted against, and referenced in
 * documentation, so it never changes once shipped even if {@code message}'s wording does.
 *
 * <p>{@code resource} names the part of the topology the finding is about, in the document's own
 * vocabulary ({@code store}, {@code controlPlane}, {@code agents}, {@code machines}, {@code tls}),
 * so a caller rendering these findings can point at the thing rather than only at the file. It is
 * empty for a rule that is genuinely about no single section -- a port claimed by two different
 * roles belongs to both of them, and naming either one would be a guess.
 */
public record Finding(String code, Severity severity, String message, Optional<String> resource) {

  public Finding(final String code, final Severity severity, final String message) {
    this(code, severity, message, Optional.empty());
  }
}
