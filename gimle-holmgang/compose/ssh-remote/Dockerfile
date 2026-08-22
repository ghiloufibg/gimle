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
    && (getent group operator || groupadd operator) \
    && useradd --create-home --shell /bin/bash --gid operator operator

# The base image's own PATH (including this Temurin build's own /opt/java/openjdk/bin) is set via
# Docker ENV metadata, which "docker exec" inherits but a real sshd session never does -- sshd spawns
# a fresh login environment of its own, unrelated to the image's Docker-level ENV layer. Debian/
# Ubuntu's sshd sources /etc/environment into every session via PAM's own pam_env module by default,
# so writing java's real location there is what actually makes "ssh host java -version" (and, more to
# the point, bin/hilmir's own "exec java") resolve java at all, not just docker exec.
RUN echo "PATH=\"${PATH}\"" >> /etc/environment

EXPOSE 22
