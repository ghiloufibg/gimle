package com.example.nodelocalcache.cache;

import com.example.nodelocalcache.FeatureFlagCache;
import com.example.nodelocalcache.FlagAnswer;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A node-local feature-flag cache: a small, fixed vocabulary seeded once at startup (standing in
 * for "a copy of centrally-managed config this replica pulled locally," the reason a real system
 * runs a node-local cache at all -- answering from local memory rather than a network hop to a
 * central config service on every single check). {@link #replicaId}, generated once per instance
 * at startup, rides along on every {@link #get} answer purely so a caller can observe which
 * replica actually answered -- see flag-consumer's own {@code FlagConsumerHooks} for what it does
 * with that.
 *
 * <p>Deployed as a {@code DaemonSet} (see ../local-flag-cache/daemonset.yaml): the platform
 * schedules exactly one instance per node, which combined with {@code gimle-fabric}'s own
 * same-worker &gt; same-machine &gt; remote locality preference is what makes a co-located caller
 * keep landing on this exact replica rather than a sibling's on some other node.
 */
public final class LocalFlagCacheHooks implements ModuleLifecycleHooks, FeatureFlagCache {

  private static final Logger log = LoggerFactory.getLogger(LocalFlagCacheHooks.class);

  // A deliberately small, fixed vocabulary -- unknown flags simply answer false, the same
  // "safe unless explicitly turned on" default a real feature-flag system defaults to.
  private static final Map<String, Boolean> FLAGS =
      Map.of(
          "new-checkout-flow", true,
          "dark-mode", true,
          "beta-search", false);

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private final String replicaId = UUID.randomUUID().toString();

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.registerService(FeatureFlagCache.class, this);
    ready.set(true);
    log.info("local-flag-cache registered its FeatureFlagCache service, replicaId={}", replicaId);
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public FlagAnswer get(String flagName) {
    boolean value = FLAGS.getOrDefault(flagName, false);
    return new FlagAnswer(value, replicaId);
  }
}
