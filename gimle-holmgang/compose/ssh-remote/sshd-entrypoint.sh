#!/bin/sh
# Authorizes the operator's own already-existing public key (mounted read-only as a compose secret,
# see ../docker-compose.ssh-remote.yml's own GIMLE_SSH_PUBKEY-driven secrets: block) for the
# "operator" user, then execs sshd in the foreground as this container's own PID 1.
#
# Nothing about the platform archive or hilmir itself runs here automatically -- unlike
# ../docker-compose.full-jre.yml/bundled-jre.yml's own entrypoint.sh, this scenario exists to prove
# hilmir's own --remote SSH dispatch works against a real sshd (not docker exec, which every other
# compose scenario here effectively substitutes for it), so the operator drives it themselves from
# outside every container, exactly the workflow --remote is built for -- see this directory's own
# README section in ../../README.md.
set -eu

install -d -m 700 -o operator -g operator /home/operator/.ssh
install -m 600 -o operator -g operator /run/secrets/operator-pubkey /home/operator/.ssh/authorized_keys

# RemoteDispatch's own "up" scp's the topology document straight into <installDir> itself
# (hilmir-remote-topology-<machine>.yaml, alongside bin/ and lib/) before ssh-exec'ing bin/hilmir
# there, so the operator user needs write access to that one directory -- not recursively into
# bin/lib/modules, which stay root-owned since nothing here needs to write into them. /opt/gimle
# matches every compose scenario's own installDir/runtime.ssh.installDir convention.
chown operator:operator /opt/gimle

# A fresh named volume mounts owned by root -- MachineLauncher's own spawned processes (each an SSH
# session running as operator, never root) write their own java argfile and data/log directories
# straight under runtime.dataRoot, so operator needs to own this scenario's own /data too.
if [ -d /data ]; then
  chown operator:operator /data
fi

exec /usr/sbin/sshd -D -e
