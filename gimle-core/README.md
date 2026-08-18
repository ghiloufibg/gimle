# Gimle Core

The foundational module of the platform: shared domain/model types, the unchecked exception
hierarchy, wire-protocol records, RBAC primitives, and the one central Logback configuration every
other Gimlé process inherits. `gimle-core` depends on nothing else in the reactor — every other
module depends on it, directly or transitively — so nothing here may reference a concept that
belongs to a specific process kind (no store, no scheduler, no fabric wire codec beyond the generic
frame helper). What lives here is either a pure value type, an exception, or infrastructure
(logging, TLS context setup) needed identically by every process.

## Package overview

| Package | Contents |
|---|---|
| `com.gimle.core.module` | `ModuleDescriptor`, `ModuleId`, `Version`/`VersionRange`, `ResourceSpec`/`ResourceQuantity`, `IsolationTier`, `ServiceExport`, `Requirement`, `ArtifactReference`, `VolumeRequest`, `HealthProbes` — the parsed shape of a `gimle-module.yaml`, used by `gimle-module`'s parser/resolver but defined here so `gimle-core`-level code (protocol records, exceptions) can reference a module identity without depending on `gimle-module`. |
| `com.gimle.core.exception` | Every unchecked exception type Gimlé throws (see below). |
| `com.gimle.core.protocol` | Wire-level records shared by the agent↔worker control channel and the control-plane HTTP API: `ControlMessage` (sealed), `InstanceObservation`, `NodeHeartbeat`/`NodeRegistration`/`NodeCapabilities`, `AuditEvent`, `CsrSubmission`/`CsrResult`, and `Json`, a small hand-rolled JSON reader/writer used instead of a third-party library. |
| `com.gimle.core.authz` | RBAC model: `Principal`, `Role`/`RoleBinding`, `Permission`, `Verb`, `ResourceKind`, `Account`, `BuiltinRoles`, `PasswordHashes`. |
| `com.gimle.core.tenant` | `Tenant`, `ResourceQuota`, `NetworkPolicyRule` — the wire-transferable shape of a network policy relayed from control plane to agent to worker. |
| `com.gimle.core.tls` | `TransportProtocol`, `TlsSettings`, `SslContexts` — plaintext-vs-mTLS switch and `SSLContext` construction shared by every process's own HTTP/socket server. |
| `com.gimle.core.config` | `ConfigEntry` — the store-level shape backing both plain config and (layered on top, in `gimle-fafnir`) encrypted secrets. |
| `com.gimle.core.logging` | The platform's logging setup (see below). |
| `com.gimle.core.vessel` | `VesselSpec` and friends — the descriptor shape for a Vessel (a non-JVM/opaque process workload), including probe specs (`VesselProbeSpec`, sealed: `Tcp`/`Http`) and env-value resolution (`VesselEnvValue`, sealed). |
| `com.gimle.core.session`, `com.gimle.core.throttle` | Console session token issuance/verification and login-attempt throttling, shared by every process that serves its own `/auth/*`. |
| `com.gimle.core.banner`, `com.gimle.core.web`, `com.gimle.core.codec`, `com.gimle.core.hash`, `com.gimle.core.io`, `com.gimle.core.saga` | Startup banner rendering, bundled-SPA static serving (`BundledSpa`/`SpaStaticHandler`), the fabric wire codec's shared frame helper (`Frames`), `Sha256`, a digest-bounded `InputStream`, and the Saga event/codec types folded into Holmgang's unified run report. |

## No checked exceptions

Every Gimlé-specific failure is an unchecked type extending `RuntimeException`:
`GimleResolutionException`, `GimleLifecycleException`, `GimleSchedulingException`,
`GimleManifestException`, `GimleClusterException`, `GimleIsolationException`,
`GimleCodecException`, `GimleSecretsException`, `GimleVolumeException`,
`GimleFabricAuthorizationException`, `GimleRaftException`, `GimleTlsException`. Each is a small
`final` class with private constructors and named static factories (e.g.
`GimleLifecycleException.hookFailed(moduleId, hookName, cause)`,
`.illegalTransition(moduleId, from, to)`) rather than a public multi-arg constructor — the factory
name documents which failure occurred at the call site, and construction stays centralized so the
message format for a given failure kind lives in exactly one place. Control-plane HTTP handlers
catch these at the boundary and map them to structured API error responses; nothing else in the
codebase declares or catches a checked exception.

## Logging

`gimle-core` ships the one `logback.xml` every process gets on its runtime classpath — no module
downstream redeclares it. `GimleLogging` is the programmatic half: each process's `Main` class calls
`attachPlatformFileAppender`/`attachInstanceSiftingAppender` once, right after parsing its own node
id/role from CLI args, to attach file appenders whose target path isn't known until then (Logback's
own XML-driven auto-configuration has already run and attached the console appender by the time any
`main` method body executes — this only ever adds appenders on top). `InstanceSiftingFileAppender`
splits a worker process's own log stream per hosted module instance via MDC (`InstanceMdcKeys`,
`InstanceMdcContext`), and `LogFileReader` parses the resulting day-bucketed JSON-lines files back
out — reused as-is by `gimle-muninn`'s own day-file store for metrics and traces, not just logs.

## Sealed interfaces and immutability

`ControlMessage`, `VesselProbeSpec`, `VesselEnvValue`, `VersionRange`, and `SagaEvent` are sealed
interfaces over a closed, exhaustively-`switch`able set of record variants — each models a small
closed family of wire/config shapes (e.g. `VersionRange.ExactVersion`/`BoundedRange`) where an
unhandled case should be a compile error, not a runtime surprise. Every model type here is a record;
collections passed into or held by one are defensively copied to an unmodifiable form in the compact
constructor. `ControlMessage` itself carries module state as a plain `String` rather than
`gimle-module`'s own `ModuleState` enum — `gimle-core` has no dependency on `gimle-module`, and the
agent only ever needs to relay that state, not interpret it.
