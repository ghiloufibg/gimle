#!/usr/bin/env bash
# Rules-review agent gate for changes about to land on the master branch, whichever git
# subcommand is doing it (commit, merge, cherry-pick, revert, rebase, reset, pull, am).
#
# Fires PreToolUse on every Bash call. Cheaply exits unless: (a) the command is one of the known
# history-mutating git subcommands, and (b) the current branch is master. When both hold, this
# script computes the change's *prospective* diff WITHOUT running the real mutating command
# against the real repo:
#   - git commit: the diff is already knowable via `git diff --cached` -- nothing to simulate.
#   - merge/cherry-pick/revert/rebase/reset/pull/am: replayed inside a throwaway, detached
#     `git worktree` seeded at master's current commit. It shares this repo's object store (cheap,
#     no cloning) but is a separate working directory/index, and being detached, nothing it does
#     can touch the real refs/heads/master. The prospective diff is `git diff <base-sha> HEAD`
#     inside that worktree; the worktree is then discarded. No command in this script ever
#     force-resets or deletes a *real* branch ref -- only a disposable directory.
#
# Caveat: the replay executes the FULL original command string (not just the git portion) inside
# the throwaway worktree, so any non-git side effects in a compound command (e.g.
# "npm install && git merge x") run there too, in addition to the real run once allowed. That's
# intentional -- reliably splitting "the git part" out of an arbitrary shell command is its own can
# of fragility -- but it means side effects reaching outside the working directory (network calls,
# absolute-path writes) aren't sandboxed by this and will happen twice.
#
# An independent, read-only `claude -p` subprocess reviews the diff against CLAUDE.md's
# Conventions section. On FAIL, the ORIGINAL command is denied outright via PreToolUse's
# permissionDecision -- before it ever touches the real repo. On PASS, or on reviewer
# timeout/crash/malformed output (fail-open), the original command is allowed to run for real.
set -euo pipefail

payload="$(cat)"
command=$(jq -r '.tool_input.command // empty' <<<"$payload")
cwd=$(jq -r '.cwd // "."' <<<"$payload")

allow() { exit 0; }
deny() {
  jq -n --arg reason "$1" '{
    hookSpecificOutput: {
      hookEventName: "PreToolUse",
      permissionDecision: "deny",
      permissionDecisionReason: $reason
    }
  }'
  exit 0
}

branch=$(git -C "$cwd" symbolic-ref --short -q HEAD || true)
[[ "$branch" == "master" ]] || allow

# Which known history-mutating subcommand is this? (allows a leading "cd x &&"/";" prefix and a
# "-C <dir>" between "git" and the subcommand.)
subcmd=$(grep -Eo '(^|[;&|]\s*)git\s+(-C\s+\S+\s+)?(commit|merge|cherry-pick|revert|rebase|reset|pull|am)\b' <<<"$command" \
  | grep -Eo '(commit|merge|cherry-pick|revert|rebase|reset|pull|am)$' | tail -n1 || true)
[[ -n "$subcmd" ]] || allow

if [[ "$subcmd" == "commit" ]]; then
  diff=$(git -C "$cwd" diff --cached)
else
  base_sha=$(git -C "$cwd" rev-parse HEAD)
  preview_dir=$(mktemp -d /tmp/gimle-master-guard-preview.XXXXXX)
  preview_log=$(mktemp /tmp/gimle-master-guard-preview-log.XXXXXX)
  cleanup() {
    git -C "$cwd" worktree remove --force "$preview_dir" >/dev/null 2>&1 || rm -rf "$preview_dir"
    rm -f "$preview_log"
  }
  trap cleanup EXIT

  if git -C "$cwd" worktree add --detach -q "$preview_dir" "$base_sha" \
      && (cd "$preview_dir" && eval "$command") >"$preview_log" 2>&1; then
    diff=$(git -C "$preview_dir" diff "$base_sha" HEAD)
  else
    # Either the worktree setup failed, or the real command would itself fail (e.g. a genuine
    # merge conflict) -- let it fail for real too rather than second-guessing it here.
    allow
  fi
fi

[[ -n "$diff" ]] || allow # no net content change -- nothing to review

review_prompt="Read CLAUDE.md's Conventions section in this repository, then review the diff piped to your stdin against those conventions (and the architecture CLAUDE.md describes, where relevant). Flag real violations only -- do not nitpick style choices CLAUDE.md doesn't cover. End your reply with exactly one final line, nothing after it: 'VERDICT: PASS' or 'VERDICT: FAIL: <one-sentence reason>'."

result=$(cd "$cwd" && printf '%s' "$diff" | timeout 170s claude -p "$review_prompt" \
  --output-format json \
  --allowedTools "Read,Grep,Glob" 2>/dev/null) || {
  echo "master-guard: reviewer subprocess failed or timed out -- allowing (fail-open)." >&2
  allow
}

verdict_line=$(jq -r '.result // empty' <<<"$result" | grep -o 'VERDICT:.*' | tail -n1)
if [[ "$verdict_line" == VERDICT:\ FAIL* ]]; then
  deny "Independent rules-review agent rejected this change to master: ${verdict_line#VERDICT: FAIL: }"
fi

allow
