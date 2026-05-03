---
name: price-compare-banks
description: Сравнивает цены монет по банкам с базой в Сбере. Используй, когда нужно найти, в каких банках цена совпадающих монет отличается от Сбера (ВТБ, Ланта, РСХБ), с запуском явных тулов refresh -> compare -> report.
disable-model-invocation: true
---

# Сравнение цен монет по банкам

## Назначение

Skill работает как tool-driven pipeline и решает одну главную задачу:
- найти совпадающие монеты между Сбером и банками из whitelist;
- показать, в каких банках цена отличается от Сбера.

База сравнения всегда одна:
- `coins_sberbank_catalog.json`

Поддерживаемые банки:
- `vtb`
- `lanta`
- `rshb`

## Tools

### 1) `refresh_price_data`

Назначение:
- проверить наличие JSON-файлов;
- при отсутствии или при `--force` перезапустить скрейперы;
- вернуть статус обновления по каждому банку.

CLI:
- `python3 tools/refresh_price_data.py [--banks vtb,lanta,rshb] [--force] [--timeout-sec 1800] [--json]`

Вход:
- `banks` (опционально): список банков для обновления; `sber` добавляется автоматически как baseline.
- `force` (опционально): обновлять даже если файл уже существует.
- `timeout_sec` (опционально): таймаут на запуск каждого скрейпера.

Логика запуска скрейпера по банку:
- сначала run-скрипт;
- fallback на python-команду.

Порядок:
- `sber` -> `vtb` -> `lanta` -> `rshb`.

Выход:
- JSON со структурой:
  - `baseline_ready` (`true/false`)
  - `statuses[]` c полями `bank`, `file`, `status` (`updated|already_present|failed|skipped`), `command_used`, `error`
  - `missing_files[]`

Ошибки:
- если baseline-файл Сбера не получен, tool возвращает ненулевой код.

### 2) `compare_prices_vs_sber`

Назначение:
- загрузить локальные JSON;
- сопоставить монеты по приоритету ключей;
- посчитать различия цен относительно Сбера.

CLI:
- `python3 tools/compare_prices_vs_sber.py [--banks vtb,lanta,rshb] [--input-dir .] [--output coins_price_diff.json]`

Вход:
- `banks`: какие банки сравнивать с baseline.
- `input_dir`: директория с `coins_*_catalog.json`.
- `output`: путь итогового JSON сравнения.

Нормализация цены:
- Сбер: `price`
- ВТБ: `price1`
- Ланта: `sell_price`, fallback `buy_price` + `price_source=fallback_buy`
- РСХБ: `price`

Приоритет сопоставления:
1) `catalog_number`
2) `article`
3) `sku`
4) `id`
5) fallback: нормализованное `name` + `weight_g`

Выход (`coins_price_diff.json`):
- `baseline_file`, `used_files`, `missing_files`
- `diff_rows` (только `bank_price != sber_price`)
- `same_rows` (одинаковая цена)
- `not_matched_or_missing_price`

Поля строки:
- `bank`, `coin_key`, `name_sber`, `name_bank`,
  `sber_price`, `bank_price`, `diff_abs`, `diff_pct`,
  `verdict`, `match_reason`, `price_source`

Ошибки:
- отсутствие baseline-файла после refresh.

### 3) `build_diff_report`

Назначение:
- собрать человекочитаемый отчет из JSON сравнения;
- сохранить фокус на расхождениях, без top-N и рейтингов.

CLI:
- `python3 tools/build_diff_report.py [--input coins_price_diff.json] [--output price_diff_report.md]`

Выход:
- markdown-файл с разделами:
  - `Совпавшие монеты с отличием цены` (главный)
  - `Совпавшие монеты с одинаковой ценой`
  - `Не сопоставлено / нет цены`

Требование:
- не добавлять сортировки и секции вида «самые дорогие/дешевые», `top-N` и рейтинги.

## Orchestration

Обязательная последовательность выполнения:
1. `refresh_price_data`
2. `compare_prices_vs_sber`
3. `build_diff_report`

Рекомендуемый запуск из shell:
- `./run_price_refresh.sh`
- `./run_price_compare.sh`
- `./run_price_report.sh`

Если baseline не готов после шага refresh, шаги compare/report не выполнять.

## Правила вывода

- Для сравнения использовать только локальные JSON (без network-calls).
- Явно показывать, какие файлы использованы, какие отсутствовали и какие команды были запущены.
- Неоднозначные сопоставления не скрывать: отправлять в `Не сопоставлено / нет цены`.
- Главный output — пары `монета + банк`, где цена отличается от Сбера.

## Примечания

- Skill вызывается явно (explicit invocation).
- Детальные mapping/edge-cases — в `reference.md`.
- Готовые сценарии запуска — в `examples.md`.
