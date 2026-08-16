#!/bin/sh
# Extracts the built gimle-platform-<version>.tar.gz -- mounted read-only at /dist from the host's
# gimle-dist/target/, see GIMLE_DIST_TARGET in the compose file -- into the shared gimle-install
# volume every other service in this compose file mounts read-only at /opt/gimle, stripping the
# archive's own top-level gimle-platform-<version>/ directory so /opt/gimle's own root holds
# bin/lib/modules(/jre) directly. A one-shot service the rest depend on
# (condition: service_completed_successfully) rather than requiring the operator to unpack the
# archive by hand and export a path to it themselves.
set -eu

candidates=$(find /dist -maxdepth 1 -name 'gimle-platform-*.tar.gz' 2>/dev/null)
count=$(printf '%s\n' "$candidates" | grep -c . || true)

if [ "$count" -eq 0 ]; then
  echo "unpack: no gimle-platform-*.tar.gz found under /dist -- build it first:" >&2
  echo "  mvn -pl gimle-dist -am install                     (for docker-compose.full-jre.yml)" >&2
  echo "  mvn -pl gimle-dist -am install -P dist-with-jre     (for docker-compose.bundled-jre.yml)" >&2
  exit 1
fi
if [ "$count" -gt 1 ]; then
  echo "unpack: more than one gimle-platform-*.tar.gz found under /dist -- clean gimle-dist/target" >&2
  echo "and rebuild the one archive variant this compose file needs:" >&2
  printf '%s\n' "$candidates" >&2
  exit 1
fi

archive="$candidates"
echo "unpack: extracting $archive into /opt/gimle"
tar xzf "$archive" -C /opt/gimle --strip-components=1
