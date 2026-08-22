package com.example.mapreduce;

import java.util.List;
import java.util.Map;

/**
 * The fabric service contract shared by mapreduce-worker and mapreduce-coordinator. Each module
 * bundles its own literal copy of this interface (same fully-qualified name, same signature)
 * rather than depending on a shared compile-time API jar -- the fabric's service catalog resolves
 * lookups by interface name and dispatches through a proxy built from the caller's own {@code
 * Class} object (see {@code FabricServiceRegistry}), so two independently compiled, structurally
 * identical copies interoperate correctly across the wire. The same "structural contract, not a
 * shared jar" demonstration gimle-examples/greeter-provider and greeter-consumer already
 * establish.
 */
public interface MapReduceWorker {

  /**
   * The map phase: tokenizes {@code lines} into lowercase words and counts occurrences, returning
   * a partial word-frequency map for just this one chunk. The coordinator merges (reduces) the
   * partial maps returned by every chunk's own call into the final result.
   */
  Map<String, Long> mapChunk(List<String> lines);
}
