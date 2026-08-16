package com.gimle.hilmir.release;

/**
 * One {@code config[]} entry: a plain (unencrypted) config value to set for {@code tenant}/{@code
 * key}, {@code value} still possibly carrying an unresolved {@code ${values.*}} reference until
 * {@link BundleRenderer} resolves it.
 */
public record BundleConfigEntry(String tenant, String key, String value) {}
