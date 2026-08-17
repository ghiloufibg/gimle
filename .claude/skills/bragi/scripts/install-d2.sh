#!/usr/bin/env bash
# Installs the D2 CLI from a pinned GitHub release into D2_INSTALL_DIR (default ~/.local/bin).
# Pinned rather than "latest" because release discovery endpoints (api.github.com,
# releases/latest redirects, d2lang.com/install.sh) are commonly blocked by egress proxies,
# while direct releases/download/ URLs work. Override with D2_VERSION=vX.Y.Z when needed.
set -euo pipefail

VERSION="${D2_VERSION:-v0.7.1}"
INSTALL_DIR="${D2_INSTALL_DIR:-$HOME/.local/bin}"

if command -v d2 >/dev/null 2>&1; then
  echo "d2 already installed: $(command -v d2) ($(d2 --version))"
  exit 0
fi

case "$(uname -s)" in
  Linux) OS=linux ;;
  Darwin) OS=macos ;;
  *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64) ARCH=amd64 ;;
  arm64 | aarch64) ARCH=arm64 ;;
  *) echo "unsupported arch: $(uname -m)" >&2; exit 1 ;;
esac

TARBALL="d2-${VERSION}-${OS}-${ARCH}.tar.gz"
URL="https://github.com/terrastruct/d2/releases/download/${VERSION}/${TARBALL}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "downloading ${URL}"
curl -fsSL -o "${TMP}/${TARBALL}" "$URL"
tar -xzf "${TMP}/${TARBALL}" -C "$TMP"

mkdir -p "$INSTALL_DIR"
install -m 0755 "${TMP}/d2-${VERSION}/bin/d2" "${INSTALL_DIR}/d2"
echo "installed $("${INSTALL_DIR}/d2" --version) to ${INSTALL_DIR}/d2"

case ":$PATH:" in
  *":${INSTALL_DIR}:"*) ;;
  *) echo "note: ${INSTALL_DIR} is not on PATH" ;;
esac
