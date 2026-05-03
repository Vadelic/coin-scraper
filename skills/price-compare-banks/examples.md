# Примеры использования tool-driven пайплайна

## Пример 1: полный pipeline

Запрос пользователя:
- «Покажи, в каких банках цены на совпадающие монеты отличаются от Сбера».

Шаги:
1. Обновить/проверить данные:
   - `./run_price_refresh.sh --banks vtb,lanta,rshb`
2. Посчитать сравнение:
   - `./run_price_compare.sh --banks vtb,lanta,rshb --output coins_price_diff.json`
3. Построить отчет:
   - `./run_price_report.sh --input coins_price_diff.json --output price_diff_report.md`

Ожидаемый главный результат:
- раздел `Совпавшие монеты с отличием цены`.

## Пример 2: JSON отсутствуют изначально

Запрос пользователя:
- «Сначала обнови данные, потом сравни с базой Сбера».

Поведение `refresh_price_data`:
- при отсутствии JSON запускает скрейперы в порядке:
  - `sber` -> `vtb` -> `lanta` -> `rshb`
- для каждого банка:
  - сначала `run_*.sh`
  - fallback `python3 scrape_*.py`

Команда:
- `python3 tools/refresh_price_data.py --banks vtb,lanta,rshb --json`

## Пример 3: только часть банков

Запрос пользователя:
- «Сравни Сбер только с ВТБ и РСХБ».

Команды:
- `python3 tools/refresh_price_data.py --banks vtb,rshb --json`
- `python3 tools/compare_prices_vs_sber.py --banks vtb,rshb --output coins_price_diff.json`
- `python3 tools/build_diff_report.py --input coins_price_diff.json --output price_diff_report.md`

## Пример 4: структура rows в diff-файле

`diff_rows` содержит плоский список расхождений (не рейтинг):

```json
{
  "bank": "vtb",
  "coin_key": "5216-0060",
  "name_sber": "Победоносец",
  "name_bank": "Георгий Победоносец",
  "sber_price": 128980.0,
  "bank_price": 132900.0,
  "diff_abs": 3920.0,
  "diff_pct": 3.04,
  "verdict": "expensive",
  "match_reason": "catalog_number",
  "price_source": "primary"
}
```

## Пример 5: формат итогового report

Report должен содержать только эти секции:
- `Совпавшие монеты с отличием цены`
- `Совпавшие монеты с одинаковой ценой`
- `Не сопоставлено / нет цены`

Запрещено:
- top-N списки;
- рейтинги «дороже/дешевле всех»;
- сортировка по величине `diff_abs` или `diff_pct`.
