#!/usr/bin/env bash
#
# Stop any running claude-ui backend and start it again from the existing jar,
# without rebuilding. Shorthand for `./restart.sh --skip-build`.

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$ROOT_DIR/restart.sh" --skip-build
