# HTTP migration spikes (2026-07-30)

Проверки прямого `HttpClient` (insecure SSL) без Playwright.

## Lanta

| Проверка | Результат |
|----------|-----------|
| GET `/metals/coins/` без cookies | HTTP 200, ~13 KB, Yandex interstitial (`gorizontal-vertikal`, `noindex, noarchive`), нет `.coinList` |
| Вывод (spike) | Чистый HTTP без сессии не работает |
| Решение | **Playwright** с `--browser=/path/to/chrome` и при необходимости `--lanta.headful=true`; storage-state в `LANTA_STORAGE_STATE` / `data/lanta-storage-state.json` |

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

Playwright в MCP runtime нужен **только для Lanta** (`pom.xml` + `--browser=`).
