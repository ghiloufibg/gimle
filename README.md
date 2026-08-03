<p align="center">
  <img src="gimle-docs/static/img/logo.png" alt="Gimlé" width="120" />
</p>

<h1 align="center">Gimlé</h1>
<p align="center">Karaf/OSGi-style module lifecycle meets Kubernetes-style orchestration, all on the JVM.</p>

A fully-Java application platform: dynamic module deploy/undeploy, tiered isolation
(classloader → worker JVM → namespace), Raft-replicated control plane, and a service fabric —
no containers, no external orchestrator, no non-Java runtime.

- Architecture and design: [`gimle-PROJECT-v2.md`](gimle-PROJECT-v2.md)
- Developer docs: [`gimle-docs/`](gimle-docs/) (`mvn -P docs -pl gimle-docs install` to build
  locally — hosting isn't decided yet, see `docusaurus.config.ts`)
- Web console: [`gimle-console/`](gimle-console/)

## Build

    mvn verify

Requires JDK 25 and Bun on `PATH`.
