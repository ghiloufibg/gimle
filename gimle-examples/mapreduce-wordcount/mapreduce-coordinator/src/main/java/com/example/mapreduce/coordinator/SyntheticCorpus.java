package com.example.mapreduce.coordinator;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Generates a sizable, deterministic synthetic text corpus to stand in for "a real dataset too
 * large to process on one JVM": a fixed vocabulary and a fixed seed rather than a bundled data
 * file, so this application carries no large checked-in asset and every run -- and therefore every
 * reduced word-count result -- is byte-identical.
 */
final class SyntheticCorpus {

  // A deliberately skewed vocabulary (a handful of very common words, a long tail of rarer,
  // Gimlé-flavored ones) so the reduced result has a genuine, non-uniform top-N to report -- a
  // uniform vocabulary would make every word equally frequent and the "top words" report
  // meaningless.
  private static final String[] COMMON_WORDS = {"the", "of", "and", "a", "to", "in", "is", "that"};
  private static final String[] RARE_WORDS = {
    "gimle", "fabric", "worker", "module", "layer", "reduce", "map", "cluster", "node", "agent",
    "control", "plane", "tenant", "service", "registry", "isolation", "tier", "instance", "chunk",
    "partition", "shard", "replica", "coordinator", "job", "schedule", "manifest", "deploy",
    "lifecycle", "probe", "readiness", "liveness", "leak", "classloader", "virtual", "thread"
  };
  private static final long SEED = 42L;
  private static final int MIN_WORDS_PER_LINE = 6;
  private static final int MAX_EXTRA_WORDS_PER_LINE = 10;
  private static final int COMMON_WORD_PERCENT = 40;

  private SyntheticCorpus() {}

  /** {@code chunkCount} independent chunks of {@code linesPerChunk} lines each. */
  static List<List<String>> generate(int chunkCount, int linesPerChunk) {
    Random random = new Random(SEED);
    List<List<String>> chunks = new ArrayList<>(chunkCount);
    for (int c = 0; c < chunkCount; c++) {
      List<String> lines = new ArrayList<>(linesPerChunk);
      for (int l = 0; l < linesPerChunk; l++) {
        lines.add(generateLine(random));
      }
      chunks.add(List.copyOf(lines));
    }
    return List.copyOf(chunks);
  }

  private static String generateLine(Random random) {
    int wordsInLine = MIN_WORDS_PER_LINE + random.nextInt(MAX_EXTRA_WORDS_PER_LINE);
    StringBuilder line = new StringBuilder();
    for (int w = 0; w < wordsInLine; w++) {
      if (w > 0) {
        line.append(' ');
      }
      String[] pool = random.nextInt(100) < COMMON_WORD_PERCENT ? COMMON_WORDS : RARE_WORDS;
      line.append(pool[random.nextInt(pool.length)]);
    }
    return line.toString();
  }
}
