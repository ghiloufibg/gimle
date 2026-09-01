# Gimle PKI

The platform's certificate authority and CSR generation/signing library, backing
`gimle.transport.protocol=tls` mode end to end: minting the cluster's self-signed CA at bootstrap,
signing every leaf certificate issued after that (initial cluster material, a node's first CSR,
certificate rotation), and translating between an X.509 Subject and the platform's own `Principal`
type. It's a deliberately separate module rather than folded into `gimle-core`: pulling in Bouncy
Castle (`bcpkix-jdk18on`) would hand every module in the reactor a dependency on a substantial
third-party crypto library that most of them never touch. `CertificateAuthority` and
`CertificateSigningRequests` are confirmed to build on only public JDK crypto APIs
(`java.security.Signature`, `KeyPairGenerator`, `BigInteger`) plus Bouncy Castle's own ASN.1/DER
encoder — not the internal `sun.security.x509` route.

## Key types

- **`PkiBootstrapMain`** — the entry point for `mvn gimle:tls-init`. Generates, once, everything a
  brand-new cluster needs to start in TLS mode: the self-signed cluster CA plus distinct leaf
  certificates for the control plane, Fafnir, Muninn, Andvari, and the first human operator (a node
  agent gets nothing here — it obtains its own certificate later, live, via the CSR bootstrap flow).
  Also writes a `bootstrap-account.yaml` holding only a freshly generated admin password's
  `PasswordHashes` hash, since `ApiServer` reads this file only while its store has zero accounts
  and Raft-proposes it as a real `Account`. The plaintext password itself is delivered exactly once
  and never into anything that keeps it: printed only when standard output is genuinely a terminal,
  otherwise written to the owner-only file named by `--password-file <path>`. A non-interactive run
  that names no file is refused before generating anything, rather than printing the cluster's first
  administrator credential into whatever log captured that output.
- **`CertificateAuthority`** — a loaded or freshly generated CA (certificate + private key).
  `signCertificateRequest` is the single signing code path shared by initial bootstrap, a node
  joining, a newly approved operator, and rotation; those cases differ only in who's allowed to call
  it and under what authentication, never in the signing code itself. Every issued leaf carries
  `KeyUsage: digitalSignature` plus `ExtendedKeyUsage: serverAuth, clientAuth` — one certificate
  covers both roles, since every Gimlé component does mTLS as both client and server against the same
  single cluster CA. The CSR's own signature is verified against its declared public key before it's
  trusted; an overload lets the caller override the issued Subject's `O=` server-side rather than
  trusting whatever a CSR itself requested (used by `ApiServer#handleBootstrapCsrSubmit` so a client
  can't self-declare a privileged group).
- **`CertificateSigningRequests`** — builds a PKCS#10 CSR for an already-generated `KeyPair`,
  optionally carrying requested DNS names as a `subjectAltName` extension request — required for real
  hostname verification (a bare CN alone hasn't satisfied it since RFC 6125).
- **`OwnCertificateRotator`** — the renewal half of a process's own leaf-certificate lifecycle,
  shared by every mTLS-capable process kind (`ApiServer`, `StoreMain`, `FafnirServer`, `MuninnServer`,
  `AndvariServer`): checks whether the current leaf is due per `RenewalSchedule`, and if so, generates
  a new key pair, submits a CSR to a reachable `/bootstrap/csr` endpoint over mTLS, and writes back
  the rotated certificate and key. A no-op in plaintext mode. Returns a `boolean` rather than
  reloading any listener itself — reload is each caller's own concern.
- **`RenewalSchedule`** — picks a renewal instant randomized within the last 20–30% of a
  certificate's validity window, cached at construction so repeated polling gets a stable answer.
  Randomized rather than a fixed threshold specifically to avoid a thundering herd: nodes provisioned
  at the same moment would otherwise all try to renew in the same instant.
- **`Subjects`** — `withOrganization` rebuilds a Subject as `O=<organization>,CN=<original CN>`,
  discarding any `O=` the CSR itself requested (the server-side stamping step
  `signCertificateRequest`'s override relies on); `principalFrom` derives a `Principal` from an
  already-signed certificate (`CN=` → name, each `O=` → a group), shared so `gimle-fafnir`'s own
  independent authorization check can derive the identical `Principal` from a peer certificate
  without duplicating the RDN-walking logic.
- **`Pem`** — PEM encode/decode helpers for certificates, private keys, and CSRs.

## Known limitation

Issued leaf SANs carry DNS names only — `CertificateSigningRequests` has no `iPAddress` SAN support
— so a server reached by bare IP literal fails hostname verification even though the handshake and
CA trust chain are otherwise valid. Point clients at the SAN'd hostname (`PkiBootstrapMain`'s
`--hostname` argument, `localhost` by default) instead.

## Consumers

`gimle-controlplane`'s `ApiServer` is the CSR-signing authority (`/bootstrap/csr`), and
`gimle-agent` generates and submits its own CSR at first bootstrap. Beyond that initial-issuance
path, `OwnCertificateRotator`/`RenewalSchedule`/`Subjects` are shared by every other mTLS-capable
process kind — `gimle-mimir`, `gimle-fafnir`, `gimle-muninn`, `gimle-andvari` — for their own leaf
rotation and peer-certificate-to-`Principal` derivation, and `gimle-cli`'s `CertCommand` drives the
CSR/token operator workflow from the command line.
