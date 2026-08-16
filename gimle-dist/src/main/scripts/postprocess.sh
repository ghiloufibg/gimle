#!/bin/sh
# Runs after the three archives (platform/cli/hilmir) have been assembled: writes a
# sha256sum-compatible checksum file next to each archive, and copies the module's own combined
# CycloneDX SBOM (produced by the cyclonedx-maven-plugin execution bound earlier in this same
# package phase) to a per-archive filename. The SBOM content is identical across all three copies
# -- it covers gimle-dist's whole resolved dependency set, a superset of any single archive's own
# jars -- see gimle-dist/README.md for why a single combined SBOM was chosen over three genuinely
# scoped ones.
set -eu

target_dir="$1"
version="$2"

cd "$target_dir"

for name in "gimle-platform-$version" "gimle-cli-$version" "gimle-hilmir-$version"; do
  archive="$name.tar.gz"
  if [ ! -f "$archive" ]; then
    echo "postprocess: expected archive $archive not found in $target_dir" >&2
    exit 1
  fi
  sha256sum "$archive" > "$archive.sha256"
  cp bom.json "$name-cyclonedx.json"
done
