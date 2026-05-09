#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 scrape_atb_coins.py --insecure
