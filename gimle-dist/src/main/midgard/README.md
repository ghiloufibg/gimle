# Gimlé Midgard — the dev cluster in a box

Midgard is Gimlé's minikube equivalent: a complete single-machine Gimlé cluster running inside one
Docker container, ready for local development and manual QA against the real platform — real
process kinds, real reconcilers, real web consoles, real example workloads — with nothing on the
host but Docker.

This directory is a self-contained Docker build context. Nothing here needs a JDK, Maven, or a
Gimlé source checkout on the host.

## Start

```sh
docker compose up -d
```

That builds the image (first run only) and boots the cluster: a `gimle-mimir` store, the control
plane, Fafnir (secrets), Muninn (observability), Andvari (artifact registry), and one node agent —
all launched inside the container by the platform's own `hilmir up` against
`midgard/topology.yaml`. The three bundled example modules (`hello-module`, `greeter-provider`,
`greeter-consumer`) are then pushed to the artifact registry and deployed, so the cluster starts
with real running workloads. `docker compose ps` shows `healthy` once the control plane answers.

| URL | What |
|---|---|
| http://localhost:8080/console | The Gimlé web console (deployments, instances, nodes, logs, metrics, topology, artifacts) |
| http://localhost:8080 | Control plane API — point `gimle` CLI verbs at `--server localhost:8080` |
| http://localhost:9092/console | Fafnir's own secrets console |
| http://localhost:9093 | Muninn observability API |
| http://localhost:9094/console | Andvari's own artifact-registry console |

The cluster is plaintext and unauthenticated, exactly like every other local-dev Gimlé setup —
never publish these ports beyond your own machine.

## Use

From the host, with a `gimle` CLI (the `gimle-cli` archive, or any checkout):

```sh
gimle get deployments --server localhost:8080
gimle logs instance/greeter-consumer-deployment/0 --follow --server localhost:8080
gimle artifact push my-module.jar --server localhost:8080
gimle apply -f my-deployment.yaml --server localhost:8080
```

Or from inside the container, no host CLI needed:

```sh
docker exec gimle-midgard gimle get deployments --server 127.0.0.1:8080
docker exec gimle-midgard hilmir status --machine midgard --data-root /var/lib/gimle
```

Deploy your own module: `gimle artifact push` its jar (the coordinate comes from the jar's own
bundled `gimle-module.yaml`), then `gimle apply -f` an `apiVersion: v1` workload manifest naming
that `module: {name, version}` — the node agent pulls the jar from Andvari, so no path on any
machine is involved.

To boot an empty cluster instead of a pre-seeded one, set `MIDGARD_SEED: "false"` in
`docker-compose.yaml` (or re-run the seeding later with
`docker exec gimle-midgard /opt/gimle/midgard/seed-examples.sh`).

## Stop / reset

```sh
docker compose stop      # graceful: hilmir down tears the cluster down, state kept
docker compose start     # boot the same cluster state again
docker compose down -v   # remove container AND state volume: next up is a fresh cluster
```

Cluster state (store data, secrets, pushed artifacts, per-process logs) lives in the
`midgard-data` volume at `/var/lib/gimle`; per-process logs are
`/var/lib/gimle/<process-id>.log` inside the container
(`docker exec gimle-midgard ls /var/lib/gimle`).

## Requirements

Docker with Compose v2. Give the Docker VM at least 2 GiB of memory: the cluster runs six
platform JVMs (heap-capped in `midgard/topology.yaml`) plus one worker JVM per deployed module
instance. Running `docker run` directly instead of Compose works too, but must include `--init`
(the entrypoint's comment explains why) and the port/volume flags Compose otherwise supplies.
