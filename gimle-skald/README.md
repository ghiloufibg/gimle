# Gimle Skald

Skald is Gimlé's cluster DNS server: a small, hand-rolled UDP responder (`SkaldMain`/`SkaldServer`)
that answers standard `A` queries for `<service>.<tenant>.svc.gimle.local` (or
`<service>.svc.gimle.local` for an untenanted Service) with one of that Service's live endpoint
addresses. It exists to give workloads that can only do a plain socket-level DNS lookup — not
fabric-aware Java code with access to the in-process service registry — a way to resolve a Service
name to a reachable host, the same job `gimle-bifrost`'s proxy and `FabricServer` already do for
callers that *can* speak the fabric wire protocol or registry API directly.

Skald never talks to `gimle-mimir` itself. It polls the control plane's own `GET /services` and
`GET /services/{name}/endpoints` API on a fixed interval and caches the result in memory — the same
resolve-through-the-control-plane posture `gimle-bifrost` takes, rather than a second path into the
state store.

## How it resolves a query

1. `ControlPlaneServicePoller` calls `GET /services` to list every known Service name, then
   `GET /services/{name}/endpoints` for each one, and replaces `CachingServiceDirectory`'s entire
   in-memory map in one atomic swap. A Service whose endpoint list came back empty is cached as a
   known-but-empty entry rather than dropped: it is in the catalog, so it exists, and a Service
   that is merely mid-rollout or scaled to zero must not look like a name nobody ever declared.
   Only a Service the per-service call answers `404` for — gone between the listing and the fetch —
   is left out. A poll that fails (control plane unreachable, non-200) leaves the existing cache
   untouched rather than flipping every name to `NXDOMAIN` — a transient control plane outage never
   makes Skald less available than serving the last-known-good answer.
2. `SkaldServer` binds one `DatagramSocket` and handles each incoming datagram on its own virtual
   thread (resolution is a single volatile map read, so this exists only so one slow `send()` never
   delays the next `receive()`, not because real concurrent work happens per query).
3. A query is decoded (`DnsCodec`), the queried name is stripped of the fixed `.svc.gimle.local`
   zone suffix (`ServiceDnsNames`) to get the qualified service name the directory cache is keyed
   by, and `ServiceDirectory#resolveAll` answers with that name's whole endpoint set — an absent
   `Optional` for a name it has never heard of, a present-but-empty list for a Service it knows
   currently has no live endpoint.
4. The response is built and sent back over the same socket:
   - wrong opcode, or a query type/class other than `A`/`SRV`/`IN` → `NOTIMP`
   - a name outside the `svc.gimle.local` zone, or a name the directory has never heard of →
     `NXDOMAIN`
   - a name the directory *does* know but which currently has no endpoints (mid-rollout, scaled to
     zero — `ServiceReconciler` treats an empty endpoint set as a normal, valid outcome) →
     `NOERROR` with zero answer records, the NODATA shape, never `NXDOMAIN`: "exists, temporarily
     empty" and "no such name" are the two things an operator debugging a deploy most needs to tell
     apart
   - a known name whose cached data is severely stale (no poll has succeeded for six poll cycles)
     → `SERVFAIL` rather than a confident answer nobody has confirmed is still correct, the
     zero-endpoint case included, since "no endpoints" is itself a claim about current state
   - otherwise → `NOERROR` with every live endpoint's `A` record (or one `SRV` record per endpoint,
     each carrying that endpoint's own port)

A malformed datagram that can't even be decoded into a well-formed query is dropped silently — there
is no safe header/question to echo back in a response.

## Running it

```
SkaldMain <dnsPort> --control-plane-endpoint <host:port> [--poll-interval-seconds N]
```

- `<dnsPort>` — UDP port to bind (pass `0` in tests to get an ephemeral port back from
  `SkaldServer#port()`).
- `--control-plane-endpoint` — required; `host:port` of the control plane's HTTP API.
- `--poll-interval-seconds` — optional, defaults to 5 seconds.

Skald is plaintext-only in both directions, deliberately: its client-facing protocol is DNS-over-UDP
itself, which has no TLS story to opt into the way an HTTP-based Gimlé process does, and its own
polling connection to the control plane stays plain HTTP for now rather than growing an independent
mTLS path — the same "starts plaintext, gets a transport-security pass later" posture a new
component in this codebase typically takes. There is accordingly no plaintext-mode warning banner
here the way there is in `gimle-fafnir`/`gimle-andvari`.

## Package layout

- `com.gimle.skald` — `SkaldMain` (entry point), `SkaldServer` (the UDP responder).
- `com.gimle.skald.directory` — `ServiceDirectory`/`CachingServiceDirectory` (the resolvable
  endpoint cache), `ServiceCatalogClient`/`HttpServiceCatalogClient` (the control-plane HTTP
  client), `ControlPlaneServicePoller` (ties the two together on a schedule), `ServiceEndpoints`.
- `com.gimle.skald.dns` — `DnsCodec` (RFC 1035 query decode / response encode, capped at the
  unextended 512-byte UDP ceiling since a handful of `A` records never approaches it),
  `ServiceDnsNames` (zone-suffix stripping).
