#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

BANK_ORDER = ["sber", "vtb", "lanta", "rshb"]
DEFAULT_COMPARISON_BANKS = ["vtb", "lanta", "rshb"]

BANK_CONFIG = {
    "sber": {
        "file": "coins_sberbank_catalog.json",
        "run": ["./run_sberbank.sh"],
        "fallback": ["python3", "scrape_sberbank_coins.py"],
    },
    "vtb": {
        "file": "coins_vtb_catalog.json",
        "run": ["./run_vtb.sh"],
        "fallback": ["python3", "scrape_vtb_coins.py"],
    },
    "lanta": {
        "file": "coins_lanta_catalog.json",
        "run": ["./run_lanta.sh"],
        "fallback": ["python3", "scrape_lanta_coins.py"],
    },
    "rshb": {
        "file": "coins_rshb_catalog.json",
        "run": ["./run_scraper.sh"],
        "fallback": ["python3", "scrape_rshb_coins.py"],
    },
}


@dataclass
class BankStatus:
    bank: str
    file: str
    status: str
    command_used: str | None = None
    error: str | None = None

    def to_dict(self) -> dict:
        return {
            "bank": self.bank,
            "file": self.file,
            "status": self.status,
            "command_used": self.command_used,
            "error": self.error,
        }


def parse_banks(raw: str | None) -> list[str]:
    if not raw:
        return DEFAULT_COMPARISON_BANKS.copy()
    out: list[str] = []
    for part in raw.split(","):
        bank = part.strip().lower()
        if not bank:
            continue
        if bank not in DEFAULT_COMPARISON_BANKS:
            raise ValueError(f"Unsupported bank: {bank}")
        if bank not in out:
            out.append(bank)
    return out


def iter_target_banks(comparison_banks: Iterable[str]) -> list[str]:
    wanted = {"sber", *comparison_banks}
    return [b for b in BANK_ORDER if b in wanted]


def run_cmd(cmd: list[str], cwd: Path, timeout_sec: int) -> tuple[bool, str | None]:
    try:
        subprocess.run(cmd, cwd=str(cwd), check=True, timeout=timeout_sec)
        return True, None
    except subprocess.TimeoutExpired:
        return False, f"timeout after {timeout_sec}s"
    except subprocess.CalledProcessError as exc:
        return False, f"exit code {exc.returncode}"
    except FileNotFoundError as exc:
        return False, str(exc)


def refresh_one_bank(bank: str, root: Path, force: bool, timeout_sec: int) -> BankStatus:
    cfg = BANK_CONFIG[bank]
    out_file = root / cfg["file"]

    if out_file.exists() and not force:
        return BankStatus(bank=bank, file=cfg["file"], status="already_present")

    ok, err = run_cmd(cfg["run"], cwd=root, timeout_sec=timeout_sec)
    if ok and out_file.exists():
        return BankStatus(
            bank=bank,
            file=cfg["file"],
            status="updated",
            command_used=" ".join(cfg["run"]),
        )

    ok_fb, err_fb = run_cmd(cfg["fallback"], cwd=root, timeout_sec=timeout_sec)
    if ok_fb and out_file.exists():
        return BankStatus(
            bank=bank,
            file=cfg["file"],
            status="updated",
            command_used=" ".join(cfg["fallback"]),
        )

    error = err_fb if err_fb else err or "unknown error"
    return BankStatus(
        bank=bank,
        file=cfg["file"],
        status="failed",
        command_used=" ".join(cfg["fallback"]),
        error=error,
    )


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Refresh coin price JSON files for comparison")
    p.add_argument("--banks", default=",".join(DEFAULT_COMPARISON_BANKS), help="comparison banks list: vtb,lanta,rshb")
    p.add_argument("--force", action="store_true", help="refresh even if JSON file exists")
    p.add_argument("--timeout-sec", type=int, default=1800, help="timeout per scraper command")
    p.add_argument("--json", action="store_true", help="print JSON result")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    root = Path(__file__).resolve().parent.parent

    try:
        comparison_banks = parse_banks(args.banks)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    statuses: list[BankStatus] = []
    for bank in iter_target_banks(comparison_banks):
        statuses.append(refresh_one_bank(bank, root=root, force=args.force, timeout_sec=args.timeout_sec))

    missing_files: list[str] = []
    for bank in ["sber", *comparison_banks]:
        rel = BANK_CONFIG[bank]["file"]
        if not (root / rel).exists():
            missing_files.append(rel)

    baseline_ready = (root / BANK_CONFIG["sber"]["file"]).exists()

    result = {
        "baseline_ready": baseline_ready,
        "statuses": [s.to_dict() for s in statuses],
        "missing_files": missing_files,
    }

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    else:
        for item in result["statuses"]:
            msg = f"[{item['bank']}] {item['status']}"
            if item.get("command_used"):
                msg += f" via {item['command_used']}"
            if item.get("error"):
                msg += f" ({item['error']})"
            print(msg)
        if missing_files:
            print(f"Missing files: {', '.join(missing_files)}")

    return 0 if baseline_ready else 1


if __name__ == "__main__":
    raise SystemExit(main())
