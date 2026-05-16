#!/usr/bin/env python3
"""Устаревшая точка входа — используйте scrape_vtb_coins.py."""
from scrape_vtb_coins import main
import asyncio
import sys

if __name__ == "__main__":
    sys.exit(asyncio.run(main(sys.argv[1:])))
