#!/usr/bin/env bash
# Checklist-review gate for every commit Claude Code makes in this repo.
#
# Every change in this repo is authored by Claude Code itself -- the risk this guards against
# isn't an untrusted actor bypassing review, it's Claude's own output silently drifting from
# CLAUDE.md's Conventions section. Registered as a PreToolUse hook on the Bash tool
# (.claude/settings.json): fires before every `git commit`, spawns a separate, independent,
# read-only `claude -p` subprocess to check the staged diff against that checklist, and denies the
# commit with the concrete list of violations if it fails -- the calling session sees that as a
# normal tool-call failure, fixes the flagged issues, and retries, the same way it would react to
# a failing test.
#
# Deliberately NOT scoped to any particular branch, and deliberately NOT trying to catch merges,
# rebases, etc. (an earlier, more elaborate master-only version of this hook did -- see git
# history). With a single actor and no separate human-reviewed PR step, "review every commit as
# it's made" is both simpler and more useful than "only guard the final landing point."
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

grep -Eq '(^|[;&|]\s*)git\s+(-C\s+\S+\s+)?commit\b' <<<"$command" || allow

diff=$(git -C "$cwd" diff --cached)
[[ -n "$diff" ]] || allow # nothing staged (e.g. an empty --amend) -- nothing to review

review_prompt="Read CLAUDE.md's Conventions section in this repository -- it is a binding checklist, not a suggestion. Check the diff piped to your stdin against every rule in that section. List each rule violated, if any, quoting or closely paraphrasing the rule. Then end your reply with exactly one final line, nothing after it: 'VERDICT: PASS' if nothing is violated, or 'VERDICT: FAIL: <violation 1>; <violation 2>; ...' listing every violation found, each specific enough to act on without further context."

result=$(cd "$cwd" && printf '%s' "$diff" | timeout 170s claude -p "$review_prompt" \
  --output-format json \
  --allowedTools "Read,Grep,Glob" 2>/dev/null) || {
  echo "commit-checklist-review: reviewer subprocess failed or timed out -- allowing (fail-open)." >&2
  allow
}

verdict_line=$(jq -r '.result // empty' <<<"$result" | grep -o 'VERDICT:.*' | tail -n1)
if [[ "$verdict_line" == VERDICT:\ FAIL* ]]; then
  deny "Checklist review rejected this commit -- fix these before committing: ${verdict_line#VERDICT: FAIL: }"
fi

allow
