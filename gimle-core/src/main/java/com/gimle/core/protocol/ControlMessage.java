package com.gimle.core.protocol;

import com.gimle.core.module.ModuleId;

/**
 * Agent&harr;worker control-channel frames. Deliberately not {@code gimle-fabric}'s eventual
 * compact binary codec (Phase 4) — this is a throwaway, trivially-debuggable protocol for a handful
 * of install/start/stop commands and periodic health/metrics reports, not per-request traffic.
 * Module state travels as a plain {@code String} ({@code ModuleState#name()}), not {@code
 * gimle-module}'s own enum: {@code gimle-core} has no dependency on {@code gimle-module}, and the
 * agent doesn't need to understand module states beyond tracking/relaying them — only the worker's
 * own internals need the real type.
 */
public sealed interface ControlMessage {

  // Worker -> Agent
  record Hello(String workerId, long pid) implements ControlMessage {}

  record Ack(String correlationId) implements ControlMessage {}

  record Nack(String correlationId, String reason) implements ControlMessage {}

  record ModuleStateChanged(ModuleId id, String state) implements ControlMessage {}

  record HealthReport(ModuleId id, boolean alive, boolean ready) implements ControlMessage {}

  record MetricsReport(ModuleId id, long cpuMillicoresUsed, long memoryBytesUsed)
      implements ControlMessage {}

  record Pong(String correlationId) implements ControlMessage {}

  // Agent -> Worker
  record InstallModule(String correlationId, String artifactPath) implements ControlMessage {}

  record ResolveModule(String correlationId, ModuleId id) implements ControlMessage {}

  record StartModule(String correlationId, ModuleId id) implements ControlMessage {}

  record StopModule(String correlationId, ModuleId id) implements ControlMessage {}

  record UninstallModule(String correlationId, ModuleId id) implements ControlMessage {}

  record Ping(String correlationId) implements ControlMessage {}
}
