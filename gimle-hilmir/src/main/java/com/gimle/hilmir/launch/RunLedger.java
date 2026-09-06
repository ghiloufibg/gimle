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

  private static final String FILE_NAME = "hilmir-run.json";

  private RunLedger() {}

  static void write(final Path dataRoot, final List<RunRecord> records) {
    final List<Object> json = new ArrayList<>();
    for (final RunRecord record : records) {
      json.add(toJson(record));
    }
    try {
      Files.createDirectories(dataRoot);
      Files.writeString(dataRoot.resolve(FILE_NAME), Json.write(json), StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new HilmirException("failed writing run ledger under " + dataRoot, e);
    }
  }

  static List<RunRecord> read(final Path dataRoot) {
    final Path file = dataRoot.resolve(FILE_NAME);
    final String text;
    try {
      text = Files.readString(file, StandardCharsets.UTF_8);
    } catch (final IOException e) {
      throw new HilmirException(
          "no run recorded at "
              + dataRoot
              + " (expected "
              + file
              + "); has 'hilmir up' been run with this --data-root?",
          e);
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
    if (Files.notExists(dataRoot.resolve(FILE_NAME))) {
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
    final List<RunRecord> existing = read(dataRoot);
    final List<RunRecord> updated = new ArrayList<>();
    boolean replaced = false;
    for (final RunRecord record : existing) {
      if (record.id().equals(id)) {
        updated.add(newRecord);
        replaced = true;
      } else {
        updated.add(record);
      }
    }
    if (!replaced) {
      throw new HilmirException(
          "no run ledger entry with id '" + id + "' under " + dataRoot + " to replace");
    }
    write(dataRoot, updated);
  }

  /**
   * Read-modify-write removal of exactly one entry: drops the record whose {@code id} matches,
   * leaving every other co-located process's record untouched. Removing the last entry leaves an
   * empty ledger file rather than deleting it, so a subsequent {@code status} still reports "this
   * data root was brought up and has nothing running" rather than "no run was ever recorded here."
   */
  static void remove(final Path dataRoot, final String id) {
    final List<RunRecord> remaining = new ArrayList<>();
    boolean removed = false;
    for (final RunRecord record : read(dataRoot)) {
      if (record.id().equals(id)) {
        removed = true;
      } else {
        remaining.add(record);
      }
    }
    if (!removed) {
      throw new HilmirException(
          "no run ledger entry with id '" + id + "' under " + dataRoot + " to remove");
    }
    write(dataRoot, remaining);
  }

  static void delete(final Path dataRoot) {
    final Path file = dataRoot.resolve(FILE_NAME);
    try {
      Files.deleteIfExists(file);
    } catch (final IOException e) {
      throw new HilmirException("failed deleting run ledger at " + file, e);
    }
  }

  private static Map<String, Object> toJson(final RunRecord record) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", record.id());
    map.put("role", record.role());
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
        ((Number) map.get("pid")).longValue(),
        command,
        (String) map.get("logFile"),
        (String) map.get("readinessAddress"));
  }
}
