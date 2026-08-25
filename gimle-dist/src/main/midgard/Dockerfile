# Midgard: a complete single-machine Gimlé cluster walled inside one container -- every process
# kind Hilmir places (store, control plane, Fafnir, Muninn, Andvari, one node agent, and the
# worker JVMs that agent spawns) booted by the platform's own "hilmir up" against the bundled
# topology, plus the example modules pre-pushed to the artifact registry and deployed.
#
# A full JRE rather than a jlinked one, deliberately: the agent and worker host arbitrary module
# code this image never sees ahead of time, so a trimmed runtime could silently break a deployed
# module needing a JDK module outside the trimmed set -- the same reason the platform archive's
# own optional JRE bundling covers every process kind except those two.
FROM eclipse-temurin:25-jre

COPY bin/ /opt/gimle/bin/
COPY lib/ /opt/gimle/lib/
COPY modules/ /opt/gimle/modules/
COPY examples/ /opt/gimle/examples/
COPY midgard/ /opt/gimle/midgard/

ENV GIMLE_HOME=/opt/gimle
ENV PATH=/opt/gimle/bin:$PATH

# Control plane API + web console; Fafnir; Muninn; Andvari. Everything else (store raft/client,
# agent gossip, worker fabric ports) is cluster-internal and stays unpublished.
EXPOSE 8080 9092 9093 9094

# A bare TCP connect to the control plane port: cheap enough to run every few seconds, no JVM
# spawned per check. start-period covers the full multi-process boot plus example seeding.
HEALTHCHECK --interval=10s --timeout=5s --start-period=180s --retries=6 \
  CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080' || exit 1

ENTRYPOINT ["/opt/gimle/midgard/entrypoint.sh"]
