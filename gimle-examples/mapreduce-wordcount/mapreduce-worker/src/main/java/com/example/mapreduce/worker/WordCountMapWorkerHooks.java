package com.example.mapreduce.worker;

import com.example.mapreduce.MapReduceWorker;
import com.gimle.module.lifecycle.ModuleContext;
import com.gimle.module.lifecycle.ModuleLifecycleHooks;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the real {@link MapReduceWorker} map function on the fabric when this instance
 * starts. {@link WordCountReadinessProbe} shares {@link #ready} with this class: the instance
 * isn't ready to receive chunks until the service is actually registered and reachable.
 *
 * <p>Unlike {@code GreeterConsumerHooks}'s own background polling loop, this module never
 * initiates fabric calls of its own -- it only answers ones mapreduce-coordinator sends it, so
 * {@link #mapChunk} running synchronously on whichever thread {@code FabricServer} (gimle-fabric)
 * dispatches it on is never at risk of the "never block onStart's own receive loop" deadlock that
 * pattern exists to avoid.
 */
public final class WordCountMapWorkerHooks implements ModuleLifecycleHooks, MapReduceWorker {

  private static final Logger log = LoggerFactory.getLogger(WordCountMapWorkerHooks.class);

  // Splits on anything that isn't a letter or an apostrophe -- good enough for a synthetic,
  // already-lowercase-friendly corpus without pulling in a real NLP tokenizer.
  private static final Pattern WORD_BOUNDARY = Pattern.compile("[^a-z']+");

  static final AtomicBoolean ready = new AtomicBoolean(false);

  private final AtomicLong chunksHandled = new AtomicLong();

  @Override
  public void onInstall(ModuleContext ctx) {}

  @Override
  public void onStart(ModuleContext ctx) {
    ctx.registerService(MapReduceWorker.class, this);
    ready.set(true);
    log.info("mapreduce-worker registered its MapReduceWorker map function on the fabric");
  }

  @Override
  public void onStop(ModuleContext ctx) {
    ready.set(false);
  }

  @Override
  public void onUninstall(ModuleContext ctx) {}

  @Override
  public Map<String, Long> mapChunk(List<String> lines) {
    Map<String, Long> counts = new HashMap<>();
    for (String line : lines) {
      for (String token : WORD_BOUNDARY.split(line.toLowerCase(Locale.ROOT))) {
        if (!token.isBlank()) {
          counts.merge(token, 1L, Long::sum);
        }
      }
    }
    long handled = chunksHandled.incrementAndGet();
    // Logged, not asserted here -- the point is a human watching this replica's own log (or
    // gimle-console's Logs screen) alongside its siblings' can see distinct chunks landing on
    // distinct replicas, real evidence of parallel distributed work rather than a simulation of
    // it.
    log.info(
        "mapped a {}-line chunk into {} distinct words (chunk #{} handled by this replica)",
        lines.size(),
        counts.size(),
        handled);
    return counts;
  }
}
