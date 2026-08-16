package com.gimle.hilmir.release;

/**
 * One {@code secrets[]} entry: a secret value to set for {@code tenant}/{@code key} through the
 * control plane's Fafnir-backed {@code /secrets/*} surface, {@code value} still possibly carrying
 * an unresolved {@code ${values.*}} reference until {@link BundleRenderer} resolves it.
 */
public record BundleSecretEntry(String tenant, String key, String value) {}
