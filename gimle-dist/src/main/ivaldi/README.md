# Gimlé Ivaldi

Ivaldi is the cluster designer: a local web IDE for drawing a Gimlé cluster and its workloads,
validating the result against the platform's own parsers, and booting it on this machine.

## Start it

```sh
bin/ivaldi
```

Then open <http://127.0.0.1:9097/console>. `/` redirects there.

Requires JDK 25 or newer. `bin/ivaldi` uses `$JAVA_HOME/bin/java` when `JAVA_HOME` is set,
otherwise this archive's own bundled `jre/ivaldi/` if it has one, otherwise `java` on `PATH`.

## Options

| Flag | System property | Default |
| --- | --- | --- |
| `--port <port>` | `-Dgimle.ivaldi.port` | `9097` |
| `--data-root <dir>` | `-Dgimle.ivaldi.dataRoot` | `~/.gimle/ivaldi` |
| `--host <host>` | `-Dgimle.ivaldi.host` | loopback only |
| `--help` | | |

A flag wins over the equivalent system property. JVM options (`-D…`, `-X…`) may be passed on the
same command line; the launcher routes them to the JVM and everything else to Ivaldi.

```sh
bin/ivaldi --port 9200 --data-root /var/lib/gimle-ivaldi
```

The data root holds your blueprints, the cluster connections you configure, and each run's
workspace. Point two Ivaldi instances at two data roots to keep their designs separate.

## Security

Ivaldi has **no authentication and no TLS**, and binds to loopback only unless `--host` says
otherwise. It launches JVMs and reads and writes files as whoever started it, so binding it to a
reachable address hands that ability to anyone who can reach the port. It is a development tool
for one machine, not a service to deploy.

## What a run does

Pressing Run in the console sends the blueprint's rendered `topology.yaml`, manifests and
`bundle.yaml` to this process, which then, in-process:

1. validates the whole file set through the platform's own parsers,
2. mints TLS material when the topology declares mTLS,
3. boots the cluster's processes (or skips the boot when the topology is unchanged since the last
   run against that cluster, deploying onto what is already running),
4. pushes each jar-sourced module to the registry,
5. applies the standalone resources (services, network policies, limit ranges),
6. deploys the bundle and reports the endpoints.

Stop tears the whole process tree back down. Everything a run produces is also downloadable as a
zip whose `README.md` documents the same sequence as `hilmir`/`gimle` commands you can run by
hand.
