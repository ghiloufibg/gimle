# Commit checklist

Rules `commit-checklist-review.sh` checks every staged diff against before allowing a commit.
Kept short and mechanically checkable on purpose — this is a distillation, not a replacement, of
CLAUDE.md's Conventions section, which stays the authoritative full rule set.

- No checked exceptions — use one of gimle-core's unchecked exception types
  (`GimleResolutionException`, `GimleLifecycleException`, `GimleSchedulingException`,
  `GimleManifestException`, `GimleClusterException`, `GimleIsolationException`,
  `GimleCodecException`, `GimleSecretsException`), never `throws`.
- No Lombok. Plain Java (records, standard getters/constructors).
- Prefer immutability: records, `List.of`/unmodifiable collections. Desired state, observed state,
  and reconciliation events are immutable snapshots, never mutated in place.
- `final` on variables, fields, and parameters wherever possible.
- No JNI, no native code — OS interaction only via `java.nio.file` or FFM downcalls.
- Logging via SLF4J only — no `System.out`/`System.err`/`printStackTrace`.
- `@SuppressWarnings` is a last resort — flag any new use; a shared typed helper
  (e.g. `Json.asObject`) should absorb the unchecked cast instead.
- Method naming: `camelCase`, except methods directly annotated `@Test`, which are `snake_case`.
- No reference to a `claudedocs/*.md` path from any committed source/config file.
- Comments only where logic is genuinely non-obvious — not a substitute for clear names and small
  methods.
