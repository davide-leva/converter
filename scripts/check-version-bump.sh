#!/usr/bin/env bash
# Check a committed BASE..HEAD range, or history since the previous tag.
set -euo pipefail
REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "${REPO_ROOT}"
exec python3 "${REPO_ROOT}/scripts/check-version-bump.py" "$@"
