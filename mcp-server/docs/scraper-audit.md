# Аудит Python-скраперов для Java MCP

Источники: `tools/coin-catalog-*/scrape_*_coins.py`, `tool.json`, [`docs/coin-catalog-scraper-requirements.md`](../../docs/coin-catalog-scraper-requirements.md).

Дата: 2026-06-29

## Сводная таблица

| Банк | Slug | LOC | Механизм | Реализуемость | Риски | Java-стек (рекомендация) |
|------|------|-----|----------|---------------|-------|--------------------------|
| Золотой МД | `zoloto-md` | 356 | HTTP | Очень высокая | хрупкий HTML regex | `HttpClient` + regex |
| Сбербанк | `sberbank` | 689 | HTTP REST | Высокая | WAF, 4 POST по металлам | `HttpClient` + Jackson |
| ВТБ | `vtb` | 473 | HTTP BFF | Высокая | CAPTCHA, cookie-bound BFF | `HttpScrapeClient` + Jackson BFF |
| Aurumex | `aurumex` | 587 | HTTP JSON | Высокая | Nuxt payload deref, CAPTCHA | `HttpScrapeClient` + Jackson |
| АТБ | `atb` | 525 | HTTP Hybrid | Высокая | CAPTCHA, N+1 detail GET | `HttpScrapeClient` |
| Золотая плата | `goldenplata` | 510 | HTTP | Высокая | CAPTCHA редка; корневой `/catalog/` без JS | `HttpScrapeClient` + analytics JSON |
| Ланта | `lanta` | 658 | Playwright | Средняя | CAPTCHA без headful/session | Playwright + `--browser=` / storage-state |
| РСХБ | `rshb` | 833 | HTTP SSR + ES | Высокая | region cookie | `HttpScrapeClient` + SSR HTML |

См. также [`http-migration-spikes.md`](http-migration-spikes.md) — результаты миграции с Playwright на HTTP.

## Порядок реализации (этап 3)

1. zoloto-md → 2. sberbank → 3. vtb → 4. aurumex → 5. atb → 6. goldenplata → 7. lanta → 8. rshb

---

## zoloto-md

- **URL:** `GET https://spb.zoloto-md.ru/catalog?page=&limit=100&available=1`
- **query:** `query=` в URL
- **investment_only:** `country=Россия`
- **Данные:** статический HTML, блоки `<!-- product -->`, regex
- **buy_price:** из `js-price-buyout` при наличии

## sberbank

- **URL:** GET `sberbank.ru/ru/person/mon` (cookies) → POST `/proxy/services/coin-catalog/coins` → GET buyout
- **query:** поле `query` в POST body
- **investment_only:** `sections=["Инвестиционные монеты"]`
- **Особенность:** 4 запроса по металлам (Золото/Серебро/Платина/Палладий), merge по id; **без Playwright** (WAF)

## vtb

- **Витрина:** Playwright → BFF `POST /api/bff/api/v1/coin/list?page={N}`
- **query:** пост-фильтр локально
- **investment_only:** filter `coinKind=Инвестиционные`

## aurumex

- **URL:** Nuxt `_payload.json?availability=true`, пагинация `/catalog/page/{N}/_payload.json`
- **query:** пост-фильтр (поиска на сайте нет)
- **investment_only:** нет в tool.json; `availability=true` встроен

## atb

- **Каталог:** Playwright session + AJAX POST (`category=479` для investment)
- **query:** `name=` в POST
- **Данные:** HTML фрагмент + GET detail на каждую монету

## goldenplata

- **URL:** `goldenplata.ru/catalog/`; investment: `.../investitsionnye-monety/rossiyskiye/`
- **query:** `?q=`
- **Данные:** JSON в `<script class="js-analytics-payload">`
- **CAPTCHA:** slider, headful + `--wait-captcha-secs`

## lanta

- **URL:** `lanta.ru/petersburg/metals/coins/`; investment: `.../ivesticyonnie-monety/`
- **query:** поле `keywords` + submit
- **Данные:** DOM `.coinList` + AJAX `coinPopup.php` на каждую монету
- **CAPTCHA:** блокирует headless; stylesheet нельзя блокировать

## rshb

- **URL:** `coins.rshb.ru/?page=&page_size=99&search_text=&subjects=`
- **query:** `search_text=`
- **investment_only:** `subjects=5506`
- **region:** cookie `x-region` (default `77` Москва) — обязателен для sell price
- **buy_price:** batch POST `product/_search` (Elasticsearch)
