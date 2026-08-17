---
sidebar_position: 6
---

import ZoomableDiagram from '@site/src/components/ZoomableDiagram';

# Idempotency and content-addressing

Every distributed system eventually has to answer: *what happens when a request is retried?*
Networks drop packets, processes crash mid-response, callers time out and don't know if their
write landed. Retrying is unavoidable — but a retry is only safe if repeating the operation has the
same effect as doing it once. This page covers two complementary tools Gimlé leans on for that:
designing operations to be naturally idempotent, and making an entire store's trust model rest on
content-addressing.

## Idempotency: same input, same effect, no matter how many times

An operation is idempotent if calling it twice with the same input leaves the system in the same
state as calling it once. `PUT /users/42 {name: "Alice"}` is idempotent — replaying it just writes
the same name again. `POST /orders {item: "widget"}` typically isn't — replaying it creates a
second order. The fix is usually to make the *identity* of the thing being created part of the
request, so a duplicate is recognizable as a duplicate rather than a new event.

## Content-addressing: let the bytes be their own identity

**Andvari**, Gimlé's module artifact registry, applies this idea to an entire storage engine: a
module jar's identity is its own SHA-256 digest, computed while the bytes stream in, never
trusted from an uploaded sidecar:

```java
// gimle-andvari/src/main/java/com/gimle/andvari/ArtifactStore.java
MessageDigest digest = sha256Digest();
try (DigestInputStream digesting = new DigestInputStream(
    new SizeLimitedInputStream(body, maxArtifactBytes, ...), digest)) {
  sizeBytes = Files.copy(digesting, tempFile, StandardCopyOption.REPLACE_EXISTING);
}
sha256 = HexFormat.of().formatHex(digest.digest());
```

That single design choice is what makes a re-push of the same coordinate (`moduleId` + `version`)
safe to make idempotent by construction, rather than idempotent by convention:

```java
// gimle-andvari/src/main/java/com/gimle/andvari/ArtifactStore.java
synchronized (this) {
  Optional<StoredArtifact> existing = meta(moduleId, version);
  if (existing.isPresent()) {
    PutOutcome outcome =
        existing.get().sha256().equals(sha256) ? PutOutcome.IDENTICAL : PutOutcome.CONFLICT;
    return new PutResult(outcome, existing.get()); // wrote nothing either way
  }
  // ... first time this coordinate has been seen: commit it (below)
}
```

`PutOutcome` is a three-way answer, not a boolean: `CREATED` (genuinely new), `IDENTICAL` (a safe,
no-op retry of exactly this push), or `CONFLICT` (someone tried to change what a coordinate means,
which the store refuses outright rather than silently overwriting):

```java
// gimle-andvari/src/main/java/com/gimle/andvari/AndvariServer.java
if (result.outcome() == PutOutcome.CONFLICT) {
  respond(exchange, 409,
      "artifact " + moduleId + ":" + version + " already exists with sha256 "
      + result.stored().sha256()
      + " -- a stored version is immutable; push the changed jar as a new version");
  return;
}
```

<ZoomableDiagram
  src="/diagrams/content-addressed-registry.svg"
  alt="Pushing the same coordinate with identical bytes is a safe no-op; pushing different bytes under the same coordinate is refused with 409; a genuinely new coordinate is committed as an immutable entry"
  width={760}
/>

The whole `existing.isPresent()` check plus the eventual commit is wrapped in a single
`synchronized (this)` block specifically so two concurrent pushes of the same coordinate can never
race into a torn write — they serialize into exactly one `CREATED` and one `IDENTICAL`/`CONFLICT`,
never both believing they created it.

## Why immutability is the real payoff, not just retry safety

A pushed coordinate can *never* change its bytes — that's not a side effect of the digest check
above, it's the entire point of it. Once a coordinate is immutable, every downstream consumer
(a node agent's pull-through cache, the control plane's admission check) can trust *presence alone*
as proof of correctness: if the coordinate exists locally, it's the right bytes, full stop, with no
need to re-verify against the registry on every use. That's precisely the property that makes
Andvari's `imagePullPolicy: IfNotPresent`-style caching sound without any consensus protocol
between replicas — see [Node topology § Andvari](../architecture/node-topology.md#andvari) for how
multiple registry replicas converge on the same catalog with nothing more than periodic peer-sync,
something that would be a genuinely hard consensus problem if any replica's copy of a coordinate
could ever legitimately differ from another's.

The commit itself is atomic for the same underlying reason a caller must never observe a half-written
jar: stream to a temp file, then one atomic rename into place —

```java
Files.move(tempFile, versionDir.resolve(JAR_FILE),
    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
```

— with a startup sweep that deletes any temp file still sitting around from a process that crashed
mid-upload, since anything found there at construction time is provably an orphan.

## An honest limit: not every retry in Gimlé is this safe

It would be a nicer story if every write in the system got this treatment, but `StoreClient`'s own
`propose()` — the call every control-plane reconciler uses to mutate cluster state — is a useful
counter-example. It *does* retry automatically across transport failures and leader redirects,
resending the identical mutation object to the next endpoint. For most `StateMutation` variants
(`PutAssignment`, `RemoveAssignment`, and friends) that's perfectly safe: they're last-write-wins by
key, so replaying one is a no-op the second time, the same shape as Andvari's `IDENTICAL` case.

But two mutation kinds are append-only by design — `AppendInstanceEvent` and `AppendAuditEvent` —
and those are **not** idempotent under retry. In the narrow window where a write actually committed
on the leader but the acknowledgment was lost before the client saw it, a retry double-appends the
same event. `StoreClient` has no request-id/dedup mechanism guarding against this; it's a known,
accepted gap rather than an oversight, because closing it properly would mean giving every mutation
a client-generated idempotency key and teaching the store to deduplicate by it — real design work
for a failure window narrow enough that it hasn't been worth closing yet.

## What breaks without either of these

Skip content-addressing in a registry, and a pull-through cache can never trust "I already have
this coordinate" — it would have to re-verify against the source of truth on every single use,
turning a cache into a slower, more complicated proxy. Skip idempotency design on writes entirely,
and *every* retry becomes a correctness risk: a client that times out waiting for a response has no
way to know whether to retry or not, because retrying might duplicate an effect that already
happened. Gimlé's actual answer is neither absolute purity nor throwing up its hands — it's applying
the right amount of engineering to each specific case: full content-addressed immutability for
artifacts, natural last-write-wins idempotency for most state mutations, and an explicitly
documented, narrow gap for the two mutation kinds where true idempotency would cost more than the
risk it closes.
