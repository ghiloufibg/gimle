# Gimlé Docs

The developer documentation site: architecture, tutorials, reference material, and an aggregated
Javadoc API reference for onboarding into the Gimlé codebase. Built with
[Docusaurus](https://docusaurus.io/) (React/MDX) and Bun. See `claudedocs/docs-site-design.md` in
the repo root for the design rationale (why Docusaurus over a Lovable-scaffolded app, why this
module is `packaging=pom`, etc.).

This is an independent Bun/Docusaurus project, but it *is* a Maven module (see `pom.xml`) — one
reactor-gated behind the `docs` profile, not part of the default `mvn verify`: `exec-maven-plugin`
shells out to Bun to install and build it, and a `maven-resources-plugin` execution copies the
aggregate Javadoc (produced separately by `mvn javadoc:aggregate` at the repo root) into
`static/javadoc/` before the Docusaurus build runs, so it ends up at `/javadoc/` in the final site.

## Development

```sh
bun install
bun start        # dev server with hot reload
bun run build    # production build -> build/** (static, no Node server needed to serve it)
bun run serve    # serve the production build locally
```

## Building the complete site (with real Javadoc)

```sh
mvn gimle:docs
```

One command from the repo root: runs `mvn javadoc:aggregate`, copies the output into
`static/javadoc/`, then builds this site. See `gimle-maven-plugin`'s `DocsMojo` and this module's
own `pom.xml` description for why the two steps aren't chained by the reactor build alone.
