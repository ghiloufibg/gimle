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

exec /usr/sbin/sshd -D -e
