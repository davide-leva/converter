#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
git config --local core.hooksPath .githooks
echo 'Hook pre-push installato (core.hooksPath=.githooks).'
