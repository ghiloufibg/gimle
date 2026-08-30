package com.gimle.controlplane.config;

/**
 * One immutable snapshot from a plain {@code /config/*} key's version ledger (see {@link
 * ConfigVersionStore}). {@code value} is {@code null} exactly when {@code deleted} is {@code true}
 * -- a tombstone records that the key had no live value as of this version, not what it used to
 * hold.
 */
public record ConfigVersion(int version, String value, boolean deleted) {}
