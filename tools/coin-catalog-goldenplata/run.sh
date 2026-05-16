#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
python3 scrape_goldenplata_coins.py --insecure
