#!/usr/bin/env bash
#
# Fetch and check out the latest version of the current branch, then rebuild
# everything with ./install.sh. Does not restart — run ./start.sh afterwards.
# Linux and macOS only.
#
# Usage: ./update.sh

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT_DIR"

case "${1:-}" in
  "") ;;
  -h|--help)
    grep '^#' "$0" | sed -n '2,7p' | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  *)
    echo "Unknown option: $1" >&2
    exit 1
    ;;
esac

if [ -n "$(git status --porcelain)" ]; then
  echo "Working tree has uncommitted changes — commit or stash them before updating." >&2
  exit 1
fi

echo "Fetching latest version..."
git fetch --prune
git pull --ff-only

./install.sh
