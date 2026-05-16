#!/usr/bin/env bash
# Обёртка для scrape_vtb_coins.py — проверяет зависимости и запускает скрапер.
set -euo pipefail

TOOL_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$TOOL_DIR"

PYTHON="${PYTHON:-python3}"
SCRIPT="$TOOL_DIR/scrape_vtb_coins.py"

if [[ ! -f "$SCRIPT" ]]; then
  echo "error: не найден $SCRIPT" >&2
  exit 1
fi

ensure_deps() {
  if ! "$PYTHON" -c "import playwright" 2>/dev/null; then
    echo "Устанавливаю playwright..."
    "$PYTHON" -m pip install playwright
  fi
}

ensure_deps
exec "$PYTHON" "$SCRIPT" "$@"
