#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from pathlib import Path


def fmt_money(value):
    if value is None:
        return 'null'
    return f"{float(value):.2f}"


def fmt_pct(value):
    if value is None:
        return 'null'
    return f"{float(value):.2f}%"


def build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description='Build markdown report from coins_price_diff.json')
    p.add_argument('--input', type=Path, default=Path('coins_price_diff.json'), help='input diff JSON')
    p.add_argument('--output', type=Path, default=Path('price_diff_report.md'), help='output markdown report')
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_arg_parser().parse_args(argv)
    if not args.input.exists():
        raise SystemExit('E_DIFF_INPUT_NOT_FOUND: input diff JSON does not exist')

    data = json.loads(args.input.read_text(encoding='utf-8'))
    diff_rows = data.get('diff_rows')
    same_rows = data.get('same_rows')
    not_matched = data.get('not_matched_or_missing_price')
    if not isinstance(diff_rows, list) or not isinstance(same_rows, list) or not isinstance(not_matched, list):
        raise SystemExit('E_DIFF_INPUT_INVALID: unexpected diff JSON structure')

    lines: list[str] = []
    lines.append('# Отчет сравнения цен монет (база: Сбер)')
    lines.append('')
    lines.append('Использованные файлы:')
    for f in data.get('used_files', []):
        lines.append(f'- `{f}`')
    missing = data.get('missing_files', [])
    if missing:
        lines.append('')
        lines.append('Отсутствующие файлы:')
        for f in missing:
            lines.append(f'- `{f}`')

    lines.append('')
    lines.append('## Совпавшие монеты с отличием цены')
    if diff_rows:
        for row in diff_rows:
            lines.append(
                f"- [{row.get('bank')}] {row.get('coin_key')}: "
                f"Сбер={fmt_money(row.get('sber_price'))}, "
                f"банк={fmt_money(row.get('bank_price'))}, "
                f"diff={fmt_money(row.get('diff_abs'))} ({fmt_pct(row.get('diff_pct'))}), "
                f"verdict={row.get('verdict')}, match={row.get('match_reason')}"
            )
    else:
        lines.append('- Нет расхождений по цене среди сопоставленных пар.')

    lines.append('')
    lines.append('## Совпавшие монеты с одинаковой ценой')
    if same_rows:
        for row in same_rows:
            lines.append(
                f"- [{row.get('bank')}] {row.get('coin_key')}: "
                f"Сбер={fmt_money(row.get('sber_price'))}, банк={fmt_money(row.get('bank_price'))}"
            )
    else:
        lines.append('- Нет совпадений с одинаковой ценой.')

    lines.append('')
    lines.append('## Не сопоставлено / нет цены')
    if not_matched:
        for row in not_matched:
            lines.append(
                f"- [{row.get('bank')}] {row.get('coin_key')}: "
                f"reason={row.get('reason')}, "
                f"name_sber={row.get('name_sber')}, name_bank={row.get('name_bank')}"
            )
    else:
        lines.append('- Нет проблемных строк.')

    lines.append('')
    lines.append('Примечание: отчет не содержит top-N и рейтингов.')

    args.output.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    print(f"Saved: {args.output}")
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
