#!/usr/bin/env bash
# Обёртка для scrape_sberbank_coins.py (HTTP, stdlib — без Playwright).
set -euo pipefail

TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$TOOL_DIR"

PYTHON="${PYTHON:-python3}"
SCRIPT="$TOOL_DIR/scrape_sberbank_coins.py"

if [[ ! -f "$SCRIPT" ]]; then
  echo "error: не найден $SCRIPT" >&2
  exit 1
fi

exec "$PYTHON" "$SCRIPT" "$@"
