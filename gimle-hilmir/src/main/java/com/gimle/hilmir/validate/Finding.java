package com.gimle.hilmir.validate;

/**
 * One rule outcome from {@link TopologyValidator}. {@code code} is the public, stable contract --
 * it names the rule that fired and is meant to be grepped for, scripted against, and referenced in
 * documentation, so it never changes once shipped even if {@code message}'s wording does.
 */
public record Finding(String code, Severity severity, String message) {}
