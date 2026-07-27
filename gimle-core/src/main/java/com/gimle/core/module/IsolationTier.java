package com.gimle.core.module;

/**
 * The isolation strength requested for a module instance, from lightest to strongest: a shared
 * worker JVM (TIER_1), a dedicated worker JVM (TIER_2), or a dedicated worker JVM running inside
 * its own OS-level namespace (TIER_3).
 */
public enum IsolationTier {
  TIER_1,
  TIER_2,
  TIER_3
}
