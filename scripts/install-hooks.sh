#!/usr/bin/env bash
# One-time setup: points git at the repo's own tracked hooks (.githooks/) instead of the untracked,
# per-checkout .git/hooks/ directory, so the commit-msg/pre-commit conventions CLAUDE.md documents
# are actually enforced rather than merely written down. Run once per checkout: `scripts/install-hooks.sh`.
set -euo pipefail

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

chmod +x .githooks/pre-commit .githooks/commit-msg
git config core.hooksPath .githooks

echo "Git hooks installed: core.hooksPath=.githooks"
echo "  - commit-msg: rejects commit messages mentioning an AI assistant"
echo "  - pre-commit: runs 'mvn verify' and blocks the commit on failure"
