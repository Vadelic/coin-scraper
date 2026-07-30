# HTTP migration spikes (2026-07-30)

Проверки прямого `HttpClient` (insecure SSL) без Playwright.

## Lanta

| Проверка | Результат |
|----------|-----------|
| GET `/metals/coins/` без cookies | HTTP 200, ~13 KB, Yandex interstitial (`gorizontal-vertikal`, `noindex, noarchive`), нет `.coinList` |
| Вывод | Runtime без браузера возможен только с заранее сохранённой сессией (`LANTA_STORAGE_STATE` / `data/lanta-storage-state.json`) |
| Решение (этап 7) | **HTTP + cookies-only**: скрапер на `HttpScrapeClient`; обновление сессии вручную через `tools/coin-catalog-lanta/save_lanta_session.sh` (Playwright вне MCP runtime) |

## Goldenplata

| Проверка | Результат |
|----------|-----------|
| GET `/catalog/` | Категории, **нет** `js-analytics-payload` |
| GET `/catalog/investitsionnye-monety/` и `.../rossiyskiye/` | Analytics JSON в HTML, пагинация `PAGEN_4` |
| GET `/catalog/?q=монета` | Analytics присутствует |
| CAPTCHA на spike | Не обнаружена |
| Решение (этап 7) | **Чистый HTTP**; базовый листинг без `investmentOnly` → `/catalog/investitsionnye-monety/` (корневой `/catalog/` без JS не отдаёт карточки) |

## Сопутствующие проверки

| Источник | Spike |
|----------|-------|
| РСХБ | SSR `product-wrapper` + `price-box`, cookie `x-region` — OK |
| Aurumex | GET `_payload.json` без браузера — OK |
| АТБ | Warm-up + AJAX POST fragment — OK |

Playwright удалён из runtime MCP (`pom.xml`) после портов.
