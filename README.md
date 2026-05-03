# Coins Scrapers

## Назначение проекта

Проект собирает каталоги монет из разных банков в единый JSON-формат.
Сейчас поддерживаются:

- [`scrape_rshb_coins.py`](/Users/vadimkomyshenets/Cursor/Scraper/scrape_rshb_coins.py)
- [`scrape_sberbank_coins.py`](/Users/vadimkomyshenets/Cursor/Scraper/scrape_sberbank_coins.py)
- [`scrape_vtb_coins.py`](/Users/vadimkomyshenets/Cursor/Scraper/scrape_vtb_coins.py)
- [`scrape_lanta_coins.py`](/Users/vadimkomyshenets/Cursor/Scraper/scrape_lanta_coins.py)

Скрипты независимы друг от друга: можно запускать любой отдельно.

## Общая установка

```bash
pip install -r requirements.txt
playwright install chromium
```

## Общий запуск

- Прямой запуск Python-скрипта:

```bash
python3 <script_name>.py [flags]
```

- Запуск через shell-лаунчеры (автосоздание `.venv`, установка зависимостей):
  - [`run_scraper.sh`](/Users/vadimkomyshenets/Cursor/Scraper/run_scraper.sh) — РСХБ
  - [`run_sberbank.sh`](/Users/vadimkomyshenets/Cursor/Scraper/run_sberbank.sh) — Сбер
  - [`run_vtb.sh`](/Users/vadimkomyshenets/Cursor/Scraper/run_vtb.sh) — ВТБ
  - [`run_lanta.sh`](/Users/vadimkomyshenets/Cursor/Scraper/run_lanta.sh) — Lanta

## Тесты

```bash
pip install -r requirements-dev.txt
pytest -q
```

---

## Скрипты

### `scrape_rshb_coins.py`

**Назначение**  
Скрейпер каталога `coins.rshb.ru` через HTML-рендеринг страниц.

**Выходной файл**  
По умолчанию: `coins_rshb_catalog.json`.  
Основные поля монеты: `url`, `name`, `price`, `buyout_price`, `nominal`, `metal`, `purity`, `weight_g`, `mintage`, `catalog_number`, `sku`.

**Быстрый запуск**

```bash
python3 scrape_rshb_coins.py
```

**Параметры CLI**

| Флаг | Тип | Дефолт | Описание |
|------|-----|--------|----------|
| `--output` | path | `coins_rshb_catalog.json` | путь к итоговому JSON |
| `--page-size` | int | `99` | параметр `page_size` в URL |
| `--start-page` | int | `1` | с какой страницы начать |
| `--max-pages` | int | все | максимум страниц для обхода |
| `--delay` | float | `5.0` | пауза между страницами, сек |
| `--timeout` | int | `30000` | таймаут навигации, мс |
| `--retries` | int | `3` | попыток на страницу |
| `--headful` | flag | off | показать окно браузера |
| `--log-level` | choice | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |

**Особенности и настройки**
- блокируются тяжёлые ассеты (`image/media/font/stylesheet`) для скорости;
- используются retry + backoff;
- дедупликация по URL карточки.

### `scrape_sberbank_coins.py`

**Назначение**  
Скрейпер каталога `sberbank.ru/ru/person/mon` через API
`/proxy/services/coin-catalog/coins` с Playwright-контекстом (антибот).

**Выходной файл**  
По умолчанию: `coins_sberbank_catalog.json`.  
Ключевые поля: `url`, `name`, `catalog_number`, `price`, `buyout_price`, `metal`, `weight_g`.

**Быстрый запуск**

```bash
python3 scrape_sberbank_coins.py
```

**Параметры CLI**

| Флаг | Тип | Дефолт | Описание |
|------|-----|--------|----------|
| `--output` | path | `coins_sberbank_catalog.json` | путь к итоговому JSON |
| `--page-size` | int | `4000` | `pageSize` в payload |
| `--city` | str | `Москва` | значение `city` в payload |
| `--condition` | int | `1` | состояние монет |
| `--query` | str | пусто | строка поиска |
| `--timeout` | int | `60000` | таймаут навигации/fetch, мс |
| `--retries` | int | `3` | попыток на запрос |
| `--headful` | flag | off | показать окно браузера |
| `--no-metal-filters` | flag | off | один POST без разбивки по металлам |
| `--log-level` | choice | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |

**Особенности и настройки**
- по умолчанию делает серию POST по металлам и объединяет результат;
- отдельный GET buyout для цен выкупа;
- работает через Playwright из-за антибот-челленджа.

### `scrape_vtb_coins.py`

**Назначение**  
Скрейпер каталога ВТБ через BFF API
`POST https://www.vtb.ru/api/bff/api/v1/coin/list?page=N` из Playwright-контекста.

**Выходной файл**  
По умолчанию: `coins_vtb_catalog.json`.  
Ключевые поля: `name`, `url`, `nominal`, `metal`, `weight_g`, `price1`, `article`.

**Быстрый запуск**

```bash
python3 scrape_vtb_coins.py
```

**Параметры CLI**

| Флаг | Тип | Дефолт | Описание |
|------|-----|--------|----------|
| `--output` | path | `coins_vtb_catalog.json` | путь к итоговому JSON |
| `--timeout` | float | `30` | зарезервировано, с |
| `--retries` | int | `3` | повторов на страницу |
| `--delay` | float | `0.35` | пауза между страницами |
| `--max-pages` | int | все | лимит страниц (отладка) |
| `--playwright-timeout` | int | `45000` | таймаут навигации Playwright, мс |
| `--headful` | flag | off | показать окно браузера |
| `--log-level` | choice | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |

**Особенности и настройки**
- пагинация до `maxPage` из ответа API;
- дедупликация по `id` монеты (fallback: `article`, затем `url`);
- в итоговый JSON переносится только `price1`.

### `scrape_lanta_coins.py`

**Назначение**  
Скрейпер каталога Lanta (СПб) `https://www.lanta.ru/petersburg/metals/coins/`
через Playwright (динамический рендер + антибот/CAPTCHA).

**Выходной файл**  
По умолчанию: `coins_lanta_catalog.json`.  
Ключевые поля: `name`, `catalog_number`, `metal`, `weight_g`,
`buy_price`, `sell_price`, `url`.

**Быстрый запуск**

```bash
python3 scrape_lanta_coins.py
```

**Параметры CLI**

| Флаг | Тип | Дефолт | Описание |
|------|-----|--------|----------|
| `--output` | path | `coins_lanta_catalog.json` | путь к итоговому JSON |
| `--timeout` | int | `60000` | таймаут навигации, мс |
| `--retries` | int | `3` | попыток навигации |
| `--delay` | float | `0.5` | пауза между действиями, с |
| `--scroll-passes` | int | `8` | макс. число прокруток lazy-load |
| `--max-items` | int | все | ограничить число монет (отладка) |
| `--headful` | flag | off | показать окно браузера |
| `--log-level` | choice | `INFO` | `DEBUG` / `INFO` / `WARNING` / `ERROR` |

**Особенности и настройки**
- прокрутка страницы до стабилизации количества карточек;
- парсинг цен по меткам «Покупка/Продажа», fallback на 1-2 числа в карточке;
- если найдена только одна цена, вторая остаётся `null`, пишется `WARNING`.

---

## Как документировать новый скрипт

Когда добавляется новый `scrape_*.py`, добавьте в README новую секцию по шаблону:

1. **Назначение** (источник + способ скрейпинга/API).
2. **Выходной файл** (дефолт + ключевые поля).
3. **Быстрый запуск** (минимальная команда).
4. **Параметры CLI** (таблица: флаг, тип, дефолт, описание).
5. **Особенности и настройки** (retry, лимиты, антибот, дедуп, важные флаги).
