package com.gimle.hilmir.launch;

import com.gimle.core.protocol.Json;
import com.gimle.hilmir.HilmirException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable record of exactly which processes {@code up} spawned on one machine, so a later {@code
 * down}/{@code status} invocation -- necessarily a fresh JVM, since a launcher's own process
 * doesn't stay resident after {@code up} returns -- can find and act on them again. One JSON file
 * per data root, written once by {@link MachineLauncher#up} only after every process on that
 * machine has actually spawned (see that class's own javadoc for why never incrementally).
 */
final class RunLedger {

  private static final String FILE_PREFIX = "hilmir-run-";
  private static final String FILE_SUFFIX = ".json";

  private RunLedger() {}

  /**
   * One ledger file per machine, not one per data root.
   *
   * <p>Bringing a second machine up under the same data root -- which is what a multi-machine
   * topology run on one host does -- overwrote the first machine's record, so its processes
   * survived with nothing pointing at them and the teardown that followed reported success having
   * killed one of them.
   */
  private static Path fileFor(final Path dataRoot, final String machineName) {
    return dataRoot.resolve(FILE_PREFIX + machineName + FILE_SUFFIX);
  }

  private static List<Path> ledgerFiles(final Path dataRoot) {
    if (!Files.isDirectory(dataRoot)) {
      return List.of();
    }
    try (java.util.stream.Stream<Path> entries = Files.list(dataRoot)) {
      return entries
          .filter(
              path -> {
                final String name = String.valueOf(path.getFileName());
                return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
              })
          .sorted()
          .toList();
    } catch (final IOException e) {
      throw new HilmirException("failed listing run ledgers under " + dataRoot, e);
    }
  }

  static void write(final Path dataRoot, final String machineName, final List<RunRecord> records) {
    final List<Object> json = new ArrayList<>();
    for (final RunRecord record : records) {
      json.add(toJson(record));
    }
    try {
      Files.createDirectories(dataRoot);
      Files.writeString(fileFor(dataRoot, machineName), Json.write(json), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new HilmirException("failed writing run ledger under " + dataRoot, e);
    }
  }

  /**
   * Every process recorded under {@code dataRoot}, across every machine brought up against it --
   * the data root is what {@code down} and {@code status} address, so they act on all of it.
   */
  static List<RunRecord> read(final Path dataRoot) {
    final List<Path> files = ledgerFiles(dataRoot);
    if (files.isEmpty()) {
      throw new HilmirException(
          "no run recorded at "
              + dataRoot
              + " (expected "
              + fileFor(dataRoot, "<machine>")
              + "); has 'hilmir up' been run with this --data-root?");
    }
    final List<RunRecord> records = new ArrayList<>();
    for (final Path file : files) {
      records.addAll(readFile(file));
    }
    return records;
  }

  private static List<RunRecord> readFile(final Path file) {
    final String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new HilmirException("failed reading run ledger at " + file, e);
    }
    try {
      final List<Object> parsed = Json.asArray(Json.parse(text));
      final List<RunRecord> records = new ArrayList<>();
      for (final Object entry : parsed) {
        records.add(fromJson(Json.asObject(entry)));
      }
      return records;
    } catch (final RuntimeException e) {
      throw new HilmirException("run ledger at " + file + " is corrupt: " + e.getMessage(), e);
    }
  }

  /**
   * Same as {@link #read}, but an absent ledger file (a machine's first-ever {@code up}, or a data
   * root {@code down} already cleared) yields an empty list instead of a thrown exception -- {@code
   * up} needs to tell "nothing recorded here yet" apart from "the file is there but corrupt," so
   * only the latter still throws.
   */
  static List<RunRecord> tryRead(final Path dataRoot) {
    if (ledgerFiles(dataRoot).isEmpty()) {
      return List.of();
    }
    return read(dataRoot);
  }

  /**
   * Read-modify-write upsert of exactly one entry: replaces the record whose {@code id} matches
   * {@code id} with {@code newRecord}, leaving every other machine's co-located process record
   * byte-for-byte untouched. Used by {@code MachineLauncher#restartRole} so restarting one process
   * (e.g. a store replica) never disturbs the ledger's record of every other process this machine
   * still has running -- the write mechanism itself stays "replace the whole file" (see {@link
   * #write}), only the caller is spared reconstructing the full list itself.
   */
  static void replace(final Path dataRoot, final String id, final RunRecord newRecord) {
    for (final Path file : ledgerFiles(dataRoot)) {
      final List<RunRecord> existing = readFile(file);
      if (existing.stream().noneMatch(record -> record.id().equals(id))) {
        continue;
      }
      final List<Object> updated = new ArrayList<>();
      for (final RunRecord record : existing) {
        updated.add(toJson(record.id().equals(id) ? newRecord : record));
      }
      try {
        Files.writeString(file, Json.write(updated), StandardCharsets.UTF_8);
      } catch (final IOException e) {
        throw new HilmirException("failed writing run ledger at " + file, e);
      }
      return;
    }
    throw new HilmirException(
        "no run ledger entry with id '" + id + "' under " + dataRoot + " to replace");
  }

  /**
   * Read-modify-write removal of exactly one entry: drops the record whose {@code id} matches,
   * leaving every other co-located process's record untouched. Removing the last entry leaves an
   * empty ledger file rather than deleting it, so a subsequent {@code status} still reports "this
   * data root was brought up and has nothing running" rather than "no run was ever recorded here."
   */
  static void remove(final Path dataRoot, final String id) {
    for (final Path file : ledgerFiles(dataRoot)) {
      final List<RunRecord> existing = readFile(file);
      final List<RunRecord> remaining =
          existing.stream().filter(record -> !record.id().equals(id)).toList();
      if (remaining.size() == existing.size()) {
        continue;
      }
      try {
        Files.writeString(
            file,
            Json.write(remaining.stream().map(RunLedger::toJson).toList()),
            StandardCharsets.UTF_8);
      } catch (final IOException e) {
        throw new HilmirException("failed writing run ledger at " + file, e);
      }
      return;
    }
    throw new HilmirException(
        "no run ledger entry with id '" + id + "' under " + dataRoot + " to remove");
  }

  static void delete(final Path dataRoot) {
    for (final Path file : ledgerFiles(dataRoot)) {
      try {
        Files.deleteIfExists(file);
      } catch (final IOException e) {
        throw new HilmirException("failed deleting run ledger at " + file, e);
      }
    }
  }

  private static Map<String, Object> toJson(final RunRecord record) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", record.id());
    map.put("role", record.role());
    map.put("machine", record.machine());
    map.put("pid", record.pid());
    map.put("command", record.command());
    map.put("logFile", record.logFile());
    map.put("readinessAddress", record.readinessAddress());
    return map;
  }

  private static RunRecord fromJson(final Map<String, Object> map) {
    final List<Object> rawCommand = Json.asArray(map.get("command"));
    final List<String> command = new ArrayList<>();
    for (final Object arg : rawCommand) {
      command.add((String) arg);
    }
    return new RunRecord(
        (String) map.get("id"),
        (String) map.get("role"),
        (String) map.get("machine"),
        ((Number) map.get("pid")).longValue(),
        command,
        (String) map.get("logFile"),
        (String) map.get("readinessAddress"));
  }
}
