#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 scrape_aurumex_coins.py
