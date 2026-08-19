# Adds a minimal OpenSSH server on top of the same JDK base image the other compose scenarios use
# (see ../docker-compose.naked-infra.yml's own eclipse-temurin:25-jre) -- hilmir's own `--remote`
# SSH dispatch needs a real sshd to connect to, which no stock eclipse-temurin image ships.
# `-jdk`, not `-jre`, purely because Debian's `openssh-server` package pulls in enough of a base
# that the JRE-vs-JDK size difference stops mattering here; nothing in this scenario runs javac.
FROM eclipse-temurin:25-jdk

RUN apt-get update \
    && apt-get install -y --no-install-recommends openssh-server \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /run/sshd \
    && useradd --create-home --shell /bin/bash operator

EXPOSE 22
