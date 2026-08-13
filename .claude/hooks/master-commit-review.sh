#!/usr/bin/env bash
# Independent-agent rules review gate for commits to master.
#
# Registered as a PreToolUse hook on the Bash tool (.claude/settings.json). Since every commit in
# this project is made by Claude Code, this is the one place that reliably sees every `git commit`
# before it happens -- a plain git hook would be redundant here, not an alternative. The script
# cheaply exits for anything that isn't "git commit" on the master branch; for a master-branch
# commit, it spawns a *separate*, independent `claude -p` subprocess (read-only tools only, no
# relation to the calling session) to review the staged diff against CLAUDE.md's Conventions
# section, and denies the tool call if that review returns an explicit FAIL verdict.
#
# Fail-open on reviewer infrastructure failure (timeout/crash/malformed output), fail-closed only
# on an explicit FAIL verdict: blocking every commit because the review subprocess hiccuped would
# be worse than occasionally letting one commit through unreviewed.
set -euo pipefail

payload="$(cat)"
command=$(jq -r '.tool_input.command // empty' <<<"$payload")
cwd=$(jq -r '.cwd // empty' <<<"$payload")

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

# Only care about actual git-commit invocations (allow plain, "cd x && ...", "a; git commit ...", etc.).
grep -Eq '(^|[;&|]\s*)git\s+(-C\s+\S+\s+)?commit\b' <<<"$command" || allow

repo_dir="${cwd:-.}"
branch=$(git -C "$repo_dir" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")
[[ "$branch" == "master" ]] || allow

diff=$(git -C "$repo_dir" diff --cached)
[[ -n "$diff" ]] || allow  # nothing staged (e.g. an empty --amend) -- nothing to review

review_prompt="Read CLAUDE.md's Conventions section in this repository, then review the diff piped to your stdin against those conventions (and the architecture CLAUDE.md describes, where relevant). Flag real violations only -- do not nitpick style choices CLAUDE.md doesn't cover. End your reply with exactly one final line, nothing after it: 'VERDICT: PASS' or 'VERDICT: FAIL: <one-sentence reason>'."

result=$(cd "$repo_dir" && printf '%s' "$diff" | timeout 180s claude -p \
  --output-format json \
  --allowedTools "Read,Grep,Glob" \
  "$review_prompt" 2>/dev/null) || {
  echo "master-commit-review: reviewer subprocess failed or timed out -- allowing commit (fail-open)." >&2
  allow
}

verdict_line=$(jq -r '.result // empty' <<<"$result" | grep -o 'VERDICT:.*' | tail -n1)

if [[ "$verdict_line" == VERDICT:\ FAIL* ]]; then
  deny "Independent rules-review agent rejected this commit to master: ${verdict_line#VERDICT: FAIL: }"
fi

allow
