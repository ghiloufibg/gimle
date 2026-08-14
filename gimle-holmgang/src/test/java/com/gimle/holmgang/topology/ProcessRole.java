package com.gimle.holmgang.topology;

import com.gimle.core.exception.GimleManifestException;

/**
 * The seven spawnable process kinds a topology can configure. {@code WORKER} is never spawned
 * directly -- the agent spawns workers itself -- but is addressable here so a topology can add JVM
 * flags to the worker command tail handed to {@code AgentMain}.
 */
public enum ProcessRole {
  STORE,
  CONTROL_PLANE,
  FAFNIR,
  MUNINN,
  ANDVARI,
  AGENT,
  WORKER;

  static ProcessRole fromField(final String value) {
    return switch (value) {
      case "store" -> STORE;
      case "controlPlane" -> CONTROL_PLANE;
      case "fafnir" -> FAFNIR;
      case "muninn" -> MUNINN;
      case "andvari" -> ANDVARI;
      case "agent" -> AGENT;
      case "worker" -> WORKER;
      default ->
          throw new GimleManifestException(
              "unknown jvm flags role: "
                  + value
                  + " (expected store, controlPlane, fafnir, muninn, andvari, agent, or worker)");
    };
  }
}
