# Аудит Python-скраперов для Java MCP

Источники: `tools/coin-catalog-*/scrape_*_coins.py`, `tool.json`, [`docs/coin-catalog-scraper-requirements.md`](../../docs/coin-catalog-scraper-requirements.md).

Дата: 2026-06-29

## Сводная таблица

| Банк | Slug | LOC | Механизм | Реализуемость | Риски | Java-стек (рекомендация) |
|------|------|-----|----------|---------------|-------|--------------------------|
| Золотой МД | `zoloto-md` | 356 | HTTP | Очень высокая | хрупкий HTML regex | `HttpClient` + Jsoup |
| Сбербанк | `sberbank` | 689 | HTTP REST | Высокая | WAF, 4 POST по металлам | `HttpClient` + Jackson |
| ВТБ | `vtb` | 473 | Hybrid | Средне-высокая | CAPTCHA, cookie-bound BFF | Playwright Java + Jackson BFF |
| Aurumex | `aurumex` | 587 | Hybrid | Средняя | Nuxt payload deref, CAPTCHA | Playwright + Jackson `JsonNode` |
| АТБ | `atb` | 525 | Hybrid | Средняя | CAPTCHA, N+1 detail GET | Playwright + `HttpClient` |
| Золотая плата | `goldenplata` | 510 | Playwright | Низкая | CAPTCHA (slider), Bitrix | Playwright headful + storage-state |
| Ланта | `lanta` | 658 | Playwright | Низкая | CAPTCHA, N+1 popup AJAX | Playwright headful + storage-state |
| РСХБ | `rshb` | 833 | Hybrid | Средняя | region cookie, DOM + ES API | Playwright + `HttpClient` batch API |

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
