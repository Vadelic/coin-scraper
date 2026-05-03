# Справка: mapping и правила для тулов `price-compare-banks`

## 1) Контракты аргументов

### `refresh_price_data`

Аргументы:
- `--banks vtb,lanta,rshb` (опционально)
- `--force` (опционально)
- `--timeout-sec 1800` (опционально)
- `--json` (опционально; печать результата в stdout)

Семантика:
- `sber` всегда добавляется в набор обновления как baseline;
- `--banks` ограничивает только comparison-банки.

### `compare_prices_vs_sber`

Аргументы:
- `--banks vtb,lanta,rshb`
- `--input-dir .`
- `--output coins_price_diff.json`

Семантика:
- сравнение идет только по локальным файлам `coins_*_catalog.json`;
- если baseline отсутствует, tool завершает работу с ошибкой.

### `build_diff_report`

Аргументы:
- `--input coins_price_diff.json`
- `--output price_diff_report.md`

Семантика:
- tool только форматирует уже рассчитанный diff;
- сетевых операций и скрейпинга не выполняет.

## 2) Mapping файлов и полей цены

| Банк | Файл | Поле цены | Fallback цены |
|---|---|---|---|
| `sber` | `coins_sberbank_catalog.json` | `price` | нет |
| `vtb` | `coins_vtb_catalog.json` | `price1` | нет |
| `lanta` | `coins_lanta_catalog.json` | `sell_price` | `buy_price` |
| `rshb` | `coins_rshb_catalog.json` | `price` | нет |

Нормализация:
- учитывать только числовые цены > 0;
- для Ланты при fallback проставлять `price_source=fallback_buy`.

## 3) Mapping run/fallback команд refresh

| Банк | run-скрипт | fallback python |
|---|---|---|
| `sber` | `./run_sberbank.sh` | `python3 scrape_sberbank_coins.py` |
| `vtb` | `./run_vtb.sh` | `python3 scrape_vtb_coins.py` |
| `lanta` | `./run_lanta.sh` | `python3 scrape_lanta_coins.py` |
| `rshb` | `./run_scraper.sh` | `python3 scrape_rshb_coins.py` |

Порядок refresh:
1. `sber`
2. `vtb`
3. `lanta`
4. `rshb`

## 4) Приоритет ключей сопоставления

1. `catalog_number`
2. `article`
3. `sku`
4. `id` (явное поле или извлечение из URL)
5. fallback: нормализованное `name` + округленный `weight_g`

Для каждой пары сохранять:
- `coin_key`
- `match_reason`

## 5) Логика результата (без рейтингов)

Главный фильтр:
- `bank_price != sber_price`

Расчет полей:
- `diff_abs = bank_price - sber_price`
- `diff_pct = diff_abs / sber_price * 100` (если `sber_price > 0`)
- `verdict`: `cheaper | expensive | same`

Порядок вывода:
- сортировка только по `bank`, затем `coin_key`;
- не сортировать по величине разницы;
- не строить top-N/рейтинги.

## 6) Коды ошибок и edge-cases

`refresh_price_data`:
- `E_BASELINE_MISSING`: после попыток запуска не получен файл Сбера.
- `E_REFRESH_FAILED`: скрейпер банка завершился ошибкой (run и fallback неуспешны).

`compare_prices_vs_sber`:
- `E_BASELINE_NOT_FOUND`: отсутствует baseline-файл.
- `E_COMPARE_NO_BANK_FILES`: нет ни одного comparison-файла.
- `E_COMPARE_EMPTY`: baseline есть, но нет валидных пар для сравнения.

`build_diff_report`:
- `E_DIFF_INPUT_NOT_FOUND`: отсутствует входной diff-файл.
- `E_DIFF_INPUT_INVALID`: diff-файл невалидный/не содержит ожидаемых секций.

Edge-cases:
- Нет совпадений: заполнять `not_matched_or_missing_price`, report не считать ошибкой.
- Нет цены у пары: строка уходит в `not_matched_or_missing_price` с причиной.
- Неоднозначный match: строка уходит в `not_matched_or_missing_price` с пометкой `ambiguous_match`.

## 7) Расширение новым банком

Чтобы добавить `newbank`:
1. добавить `coins_newbank_catalog.json`;
2. добавить run/fallback команды refresh;
3. добавить mapping поля цены;
4. добавить правила идентификаторов (если не подходят текущие);
5. добавить пример вызова в `examples.md`.
