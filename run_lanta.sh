#!/usr/bin/env bash
# Стартовый скрипт: создаёт .venv при необходимости, ставит зависимости и Chromium,
# затем запускает scrape_lanta_coins.py. Все аргументы передаются скрейперу.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

VENV="$ROOT/.venv"
PYTHON="$VENV/bin/python"
PIP="$VENV/bin/pip"

if [[ ! -x "$PYTHON" ]]; then
  echo "[run_lanta] Создаю виртуальное окружение: $VENV"
  python3 -m venv "$VENV"
fi

if ! "$PYTHON" -c "import playwright" 2>/dev/null; then
  echo "[run_lanta] Устанавливаю зависимости из requirements.txt..."
  "$PIP" install --upgrade pip --quiet
  "$PIP" install -r "$ROOT/requirements.txt"
fi

echo "[run_lanta] Проверяю Chromium для Playwright..."
"$PYTHON" -m playwright install chromium

exec "$PYTHON" "$ROOT/scrape_lanta_coins.py" "$@"
