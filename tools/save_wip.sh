#!/bin/bash
# .claude/worktrees/ 以下の作業途中の変更を wip/<worktree名>.patch として保存し、現在のブランチにコミット・push する。
set -euo pipefail
REPO=$(cd "$(dirname "$0")/.." && pwd)
BRANCH=$(git -C "$REPO" rev-parse --abbrev-ref HEAD)
mkdir -p "$REPO/wip"
for w in "$REPO"/.claude/worktrees/*; do
  [ -d "$w" ] || continue
  git -C "$w" add -A
  mb=$(git -C "$w" merge-base HEAD "$BRANCH")
  git -C "$w" diff --cached --binary "$mb" > "$REPO/wip/$(basename "$w").patch"
  # 変更のない作業ツリー（マージ済みなど）は空のパッチを残さない
  [ -s "$REPO/wip/$(basename "$w").patch" ] || { rm "$REPO/wip/$(basename "$w").patch"; continue; }
  echo "$(basename "$w"): $(git -C "$w" diff --cached --stat "$mb" | tail -1) (base $mb)"
done
cd "$REPO"
git add wip
git diff --cached --quiet && { echo "no changes"; exit 0; }
git -c user.name="Claude" -c user.email="noreply@anthropic.com" commit -q -m "Save in-progress work as patches

Apply with: git apply wip/<name>.patch

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01Jso9opi5opYjEmRv2cvUS3"
git push -q origin "$BRANCH"
git log --oneline -1
