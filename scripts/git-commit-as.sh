#!/usr/bin/env bash
# Commits with a pinned committer identity.
#
# The IDE injects GIT_COMMITTER_NAME / GIT_COMMITTER_EMAIL into the shell of
# the agent, and the environment variables take precedence over
# `git config user.*`. A plain `git commit` is therefore stamped with the
# identity of the IDE instead of the one of the repository owner.
#
# The environment has to be set *before* the git process starts: neither a
# commit hook nor a git alias can change the identity of the running process.
# This wrapper therefore sets the variables and calls git directly.
#
# Usage: scripts/git-commit-as.sh -m "message" [git commit arguments]
set -euo pipefail

REPO_ROOT=$(git rev-parse --show-toplevel)

# The identity is read from the repository configuration, so a fork or a
# different owner only has to change the two keys.
COMMITTER_NAME=$(git config user.name || echo "ScLiuxx")
COMMITTER_EMAIL=$(git config user.email || echo "ScLiuxx@users.noreply.github.com")

export GIT_COMMITTER_NAME="$COMMITTER_NAME"
export GIT_COMMITTER_EMAIL="$COMMITTER_EMAIL"

exec git commit "$@"
