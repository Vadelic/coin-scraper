#!/usr/bin/env bash
# Совместимость: делегирует в run_vtb.sh
set -euo pipefail
exec "$(cd "$(dirname "$0")" && pwd)/run_vtb.sh" "$@"
