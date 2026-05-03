#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import parse_qs, urlparse

BANK_FILE = {
    "sber": "coins_sberbank_catalog.json",
    "vtb": "coins_vtb_catalog.json",
    "lanta": "coins_lanta_catalog.json",
    "rshb": "coins_rshb_catalog.json",
}
DEFAULT_BANKS = ["vtb", "lanta", "rshb"]


def parse_banks(raw: str) -> list[str]:
    out: list[str] = []
    for part in raw.split(","):
        bank = part.strip().lower()
        if not bank:
            continue
        if bank not in DEFAULT_BANKS:
            raise ValueError(f"Unsupported bank: {bank}")
        if bank not in out:
            out.append(bank)
    return out or DEFAULT_BANKS.copy()


def load_coins(path: Path) -> list[dict[str, Any]]:
    data = json.loads(path.read_text(encoding='utf-8'))
    coins = data.get('coins')
    if not isinstance(coins, list):
        return []
    return [c for c in coins if isinstance(c, dict)]


def to_float(value: Any) -> float | None:
    if value is None:
        return None
    try:
        v = float(value)
    except (TypeError, ValueError):
        return None
    if v <= 0:
        return None
    return v


def normalize_name(name: Any) -> str:
    if not isinstance(name, str):
        return ''
    return re.sub(r'\s+', ' ', name.strip().casefold())


def weight_bucket(value: Any) -> str | None:
    v = to_float(value)
    if v is None:
        return None
    return f"{round(v, 3):.3f}"


def extract_id(rec: dict[str, Any]) -> str | None:
    rid = rec.get('id')
    if rid is not None and str(rid).strip():
        return str(rid).strip()

    url = rec.get('url')
    if not isinstance(url, str) or not url.strip():
        return None

    parsed = urlparse(url)
    qs = parse_qs(parsed.query)
    for key in ('id', 'coinId'):
        vals = qs.get(key)
        if vals and vals[0].strip():
            return vals[0].strip()

    m = re.search(r'/coins/(\w[\w.-]*)', parsed.path)
    if m:
        return m.group(1)
    return None


def get_price_and_source(bank: str, rec: dict[str, Any]) -> tuple[float | None, str]:
    if bank == 'sber':
        return to_float(rec.get('price')), 'primary'
    if bank == 'vtb':
        return to_float(rec.get('price1')), 'primary'
    if bank == 'rshb':
        return to_float(rec.get('price')), 'primary'
    if bank == 'lanta':
        sell = to_float(rec.get('sell_price'))
        if sell is not None:
            return sell, 'primary'
        buy = to_float(rec.get('buy_price'))
        if buy is not None:
            return buy, 'fallback_buy'
        return None, 'missing'
    return None, 'missing'


def key_candidates(rec: dict[str, Any]) -> list[tuple[str, str]]:
    out: list[tuple[str, str]] = []
    for field in ('catalog_number', 'article', 'sku'):
        value = rec.get(field)
        if value is None:
            continue
        val = str(value).strip()
        if val:
            out.append((field, val))

    rid = extract_id(rec)
    if rid:
        out.append(('id', rid))

    nm = normalize_name(rec.get('name'))
    wb = weight_bucket(rec.get('weight_g'))
    if nm and wb:
        out.append(('name+weight', f"{nm}|{wb}"))

    return out


def index_sber_records(records: list[dict[str, Any]]) -> tuple[dict[str, dict[str, Any]], set[str]]:
    idx: dict[str, dict[str, Any]] = {}
    ambiguous: set[str] = set()
    for rec in records:
        for reason, value in key_candidates(rec):
            _ = reason
            if value in idx:
                ambiguous.add(value)
            else:
                idx[value] = rec
    return idx, ambiguous


def sort_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return sorted(rows, key=lambda r: (str(r.get('bank', '')), str(r.get('coin_key', ''))))


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description='Compare bank coin prices against Sber baseline')
    p.add_argument('--banks', default=','.join(DEFAULT_BANKS), help='comparison banks list: vtb,lanta,rshb')
    p.add_argument('--input-dir', type=Path, default=Path('.'), help='directory with coins_*_catalog.json files')
    p.add_argument('--output', type=Path, default=Path('coins_price_diff.json'), help='output JSON path')
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)

    try:
        banks = parse_banks(args.banks)
    except ValueError as exc:
        print(str(exc), file=sys.stderr)
        return 2

    input_dir = args.input_dir.resolve()
    baseline_file = input_dir / BANK_FILE['sber']
    if not baseline_file.exists():
        print('E_BASELINE_NOT_FOUND: missing coins_sberbank_catalog.json', file=sys.stderr)
        return 1

    sber_records = load_coins(baseline_file)
    sber_index, ambiguous_keys = index_sber_records(sber_records)

    used_files = [BANK_FILE['sber']]
    missing_files: list[str] = []
    diff_rows: list[dict[str, Any]] = []
    same_rows: list[dict[str, Any]] = []
    not_matched: list[dict[str, Any]] = []

    for bank in banks:
        bank_file = input_dir / BANK_FILE[bank]
        if not bank_file.exists():
            missing_files.append(BANK_FILE[bank])
            continue

        used_files.append(BANK_FILE[bank])
        for rec in load_coins(bank_file):
            bank_price, price_source = get_price_and_source(bank, rec)
            candidates = key_candidates(rec)

            matched: dict[str, Any] | None = None
            match_reason: str | None = None
            coin_key: str | None = None
            ambiguous = False

            for reason, value in candidates:
                if value in ambiguous_keys:
                    ambiguous = True
                    break
                if value in sber_index:
                    matched = sber_index[value]
                    match_reason = reason
                    coin_key = value
                    break

            if ambiguous:
                not_matched.append({
                    'bank': bank,
                    'coin_key': candidates[0][1] if candidates else None,
                    'name_bank': rec.get('name'),
                    'reason': 'ambiguous_match',
                    'price_source': price_source,
                })
                continue

            if matched is None:
                not_matched.append({
                    'bank': bank,
                    'coin_key': candidates[0][1] if candidates else None,
                    'name_bank': rec.get('name'),
                    'reason': 'not_matched',
                    'price_source': price_source,
                })
                continue

            sber_price, _ = get_price_and_source('sber', matched)
            if sber_price is None or bank_price is None:
                not_matched.append({
                    'bank': bank,
                    'coin_key': coin_key,
                    'name_sber': matched.get('name'),
                    'name_bank': rec.get('name'),
                    'reason': 'missing_price',
                    'price_source': price_source,
                })
                continue

            diff_abs = bank_price - sber_price
            diff_pct = (diff_abs / sber_price) * 100 if sber_price > 0 else None

            row = {
                'bank': bank,
                'coin_key': coin_key,
                'name_sber': matched.get('name'),
                'name_bank': rec.get('name'),
                'sber_price': sber_price,
                'bank_price': bank_price,
                'diff_abs': round(diff_abs, 6),
                'diff_pct': round(diff_pct, 6) if diff_pct is not None else None,
                'verdict': 'same' if bank_price == sber_price else ('cheaper' if bank_price < sber_price else 'expensive'),
                'match_reason': match_reason,
                'price_source': price_source,
            }

            if bank_price == sber_price:
                same_rows.append(row)
            else:
                diff_rows.append(row)

    if len(used_files) == 1:
        print('E_COMPARE_NO_BANK_FILES: no comparison files found', file=sys.stderr)
        return 1

    result = {
        'baseline_file': BANK_FILE['sber'],
        'used_files': used_files,
        'missing_files': missing_files,
        'diff_rows': sort_rows(diff_rows),
        'same_rows': sort_rows(same_rows),
        'not_matched_or_missing_price': sort_rows(not_matched),
    }

    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    print(f"Saved: {args.output}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
