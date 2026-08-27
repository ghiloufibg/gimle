package com.gimle.ragnarok.target.inventory;

import com.gimle.hilmir.topology.Machine;
import com.gimle.hilmir.topology.SshSettings;
import com.gimle.ragnarok.RagnarokException;
import com.gimle.ragnarok.config.YamlParsing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parses the {@code inventory:} block of a target document into an {@link InventorySpec} --
 * structural checks here, semantic rules (duplicate machine names, a role naming an unknown
 * machine) in {@link InventorySpec}'s own compact constructor, the same split every parser in this
 * module already follows.
 */
public final class InventorySpecParser {

  private InventorySpecParser() {}

  public static InventorySpec parse(final Map<?, ?> root) {
    final List<Machine> machines = new ArrayList<>();
    for (final Map<?, ?> m : YamlParsing.mapList(root, "machines")) {
      machines.add(parseMachine(m));
    }
    return new InventorySpec(
        machines,
        parseRoles(root, "store"),
        parseRoles(root, "controlPlane"),
        parseRoles(root, "fafnir"),
        parseRoles(root, "muninn"),
        parseRoles(root, "andvari"),
        parseAgents(root),
        YamlParsing.optionalBoolean(root, "sudo").orElse(false));
  }

  private static Machine parseMachine(final Map<?, ?> map) {
    final String name = YamlParsing.requireString(map, "name");
    final String host = YamlParsing.requireString(map, "host");
    final Optional<String> fingerprint = YamlParsing.optionalString(map, "sshHostKeyFingerprint");
    return new Machine(name, host, fingerprint, parseSshSettings(map.get("ssh")));
  }

  private static Optional<SshSettings> parseSshSettings(final Object value) {
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Map<?, ?> ssh)) {
      throw new RagnarokException("'ssh' must be a mapping");
    }
    return Optional.of(
        new SshSettings(
            YamlParsing.optionalString(ssh, "user"),
            YamlParsing.optionalInt(ssh, "port"),
            YamlParsing.optionalString(ssh, "identityFile"),
            Optional.empty(),
            Optional.empty()));
  }

  private static List<ManagedRoleSpec> parseRoles(final Map<?, ?> root, final String key) {
    final List<ManagedRoleSpec> roles = new ArrayList<>();
    for (final Map<?, ?> role : YamlParsing.mapList(root, key)) {
      roles.add(parseRole(role));
    }
    return roles;
  }

  private static ManagedRoleSpec parseRole(final Map<?, ?> map) {
    return new ManagedRoleSpec(
        YamlParsing.requireString(map, "machine"),
        YamlParsing.requireString(map, "id"),
        Path.of(YamlParsing.requireString(map, "pidFile")),
        Path.of(YamlParsing.requireString(map, "logFile")),
        YamlParsing.stringList(map, "command"),
        // Only ever meaningful for a store: role -- parsed uniformly across all five role lists
        // anyway, since a role-kind-specific parser split here would buy nothing.
        YamlParsing.optionalInt(map, "raftPort"));
  }

  private static List<AgentSpec> parseAgents(final Map<?, ?> root) {
    final List<AgentSpec> agents = new ArrayList<>();
    for (final Map<?, ?> agent : YamlParsing.mapList(root, "agents")) {
      agents.add(
          new AgentSpec(
              YamlParsing.requireString(agent, "machine"),
              YamlParsing.requireString(agent, "nodeId"),
              Path.of(YamlParsing.requireString(agent, "logRoot"))));
    }
    return agents;
  }
}
