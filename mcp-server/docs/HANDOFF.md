# Handoff: Java MCP-сервер coin-catalog

Документ для исполнения в **чистом контексте**. Python-инструменты и skills **не изменять**.

---

## 1. Цель

Локальный **MCP-сервер** (`mcp-server/`), stdio, для Cursor / Claude Desktop.

- **8 MCP tools** — те же имена и параметры, что [`tools/tools.json`](../tools/tools.json)
- Ответ каждого tool — JSON по [`src/test/resources/coins_catalog.schema.json`](../src/test/resources/coins_catalog.schema.json) (camelCase; отличается от корневой Python-схемы)
- Java-скраперы дублируют логику Python в [`tools/coin-catalog-*/`](../tools/)
- Skills в [`skills/coin-catalog-*/`](../skills/) применимы без изменений (группировка/таблицы на стороне LLM)

---

## 2. Стек (зафиксирован)

| Компонент | Версия |
|-----------|--------|
| Java | 21 |
| Spring Boot | 3.5.7 |
| Spring AI | 1.1.8 (`spring-ai-bom`) |
| MCP | `spring-ai-starter-mcp-server`, `stdio: true`, `type: SYNC` |

> Spring AI 2.x **не** использовать — требует Boot 4. Связка: Boot 3.5 + AI 1.1.

Аннотации: `org.springframework.ai.mcp.annotation.McpTool`, `@McpToolParam`.

---

## 3. Что уже сделано

### Документация
- [`docs/scraper-audit.md`](docs/scraper-audit.md) — аудит 8 Python-скраперов, порядок реализации, риски
- [`docs/tools.md`](docs/tools.md) — описание всех MCP tools, параметры, статус

### Каркас MCP (готово)
- `CoinCatalogMcpApplication` + `application.yml` (stdio, `web-application-type: none`, логи в файл)
- `CoinCatalogTools` — 8 `@McpTool` с описаниями из `tool.json`
- `CoinCatalogToolSupport` — `ScrapeResult` → pretty JSON
- `README.md`, `mvnw` (Maven Wrapper)

### Каркас скраперов (готово)
- Модели: `Coin`, `ScrapeRequest`, `ScrapeResult`, `ScrapeStatus`
- `CoinScraper`, `ScraperRegistry`, `NotImplementedScraper`
- `PriceParser`, `HttpFetcher` (retry, insecure SSL по умолчанию — как Python)
- `ScraperAutoConfiguration` — Spring DI

### Реализован 1 скрапер: zoloto-md
```
scraper/zolotmd/
  ZolotoMdPageParser.java    # pure: HTML → монеты
  ZolotoMdCatalogFetcher.java # HttpClient, пагинация
  ZolotoMdScraper.java        # оркестрация
```
- Тесты: `ZolotoMdPageParserTest`, `ZolotoMdScraperTest`
- Фикстура: `src/test/resources/fixtures/zoloto-md/catalog_page.html`
- Эталон Python: [`tools/coin-catalog-zoloto-md/scrape_zoloto-md_coins.py`](../tools/coin-catalog-zoloto-md/scrape_zoloto-md_coins.py)

### Общие тесты
- `CoinCatalogMcpApplicationTest`, `ScrapeResultSchemaTest`, `CoinCatalogToolsTest`, `PriceParserTest`

### Не проверено
- `./mvnw test package` — прерывался, нужно прогнать
- JAR в `target/` — не подтверждён

---

## 4. Архитектура скрапера (обязательно)

На каждый банк — **три слоя**:

| Слой | Ответственность |
|------|-----------------|
| `*Parser` | Чистые функции: HTML/JSON → `List<Coin>`, без сети |
| `*Fetcher` / `*Client` | I/O: HttpClient, Playwright, API |
| `*Scraper` | Оркестрация → `ScrapeResult`, перехват ошибок |

Правила:
- Immutable `record` для моделей
- Исключения не выходят из MCP — только `scrapeStatus` + `error`
- `CaptchaBlockedException` → `captcha_blocked`
- `totalCoins == coins.size()`
- При `investmentOnly` в JSON только `"investmentOnly": true` (не false)
- Логика 1:1 с Python-эталоном и [`docs/coin-catalog-scraper-requirements.md`](../docs/coin-catalog-scraper-requirements.md)

Регистрация: bean в `ScraperAutoConfiguration`, убрать `NotImplementedScraper` для этого slug.

---

## 5. MCP tools (контракт)

| Tool | Параметры | Статус |
|------|-----------|--------|
| `coin-catalog-zoloto-md` | `query`, `investmentOnly` | **реализован** |
| `coin-catalog-sberbank` | `query`, `investmentOnly` | заглушка |
| `coin-catalog-vtb` | `query`, `investmentOnly` | заглушка |
| `coin-catalog-aurumex` | `query` | заглушка |
| `coin-catalog-atb` | `query`, `investmentOnly` | заглушка |
| `coin-catalog-goldenplata` | `query`, `investmentOnly` | заглушка |
| `coin-catalog-lanta` | `query`, `investmentOnly` | заглушка |
| `coin-catalog-rshb` | `query`, `investmentOnly`, `region` (default `"77"`) | заглушка |

Заглушка: `ScrapeResult.notImplemented(request)` → `error: "not implemented"`.

Подключение Cursor — см. [`README.md`](README.md).

---

## 6. Порядок реализации скраперов

Из [`docs/scraper-audit.md`](docs/scraper-audit.md):

| # | Slug | Python эталон | Java-подход | Сложность |
|---|------|---------------|-------------|-----------|
| ✅ | zoloto-md | `scrape_zoloto-md_coins.py` | HttpClient + regex/HTML | низкая |
| 2 | sberbank | `scrape_sberbank_coins.py` | HttpClient + Jackson API | высокая реализуемость |
| 3 | vtb | `scrape_vtb_coins.py` | Playwright + BFF JSON | средняя |
| 4 | aurumex | `scrape_aurumex_coins.py` | Playwright + Nuxt payload | средняя |
| 5 | atb | `scrape_atb_coins.py` | Playwright + AJAX | средняя |
| 6 | goldenplata | `scrape_goldenplata_coins.py` | Playwright + analytics JSON | низкая реализуемость |
| 7 | lanta | `scrape_lanta_coins.py` | Playwright + popup AJAX | высокая сложность |
| 8 | rshb | `scrape_rshb_coins.py` | Playwright + ES API, region | высокая сложность |

**Один банк = один PR/коммит.** После каждого:
1. Тесты (parser unit + scraper mock + schema)
2. Регистрация в `ScraperAutoConfiguration`
3. Обновить статус в `docs/tools.md`
4. `./mvnw test`

---

## 7. Тестирование (обязательно на банк)

1. **Unit** — `*ParserTest` на `src/test/resources/fixtures/{bank}/`
2. **Scraper** — `*ScraperTest` с mock fetcher (happy, error, captcha, empty, filters)
3. **Schema** — выход проходит `coins_catalog.schema.json`
4. **Live** — `@Tag("live")`, только вручную; не в CI

CI: `mvn test` без live/browser тегов. JaCoCo ≥80% в пакете банка.

---

## 8. Следующие шаги (для исполнителя)

### Шаг A — проверить сборку
```bash
cd mcp-server
./mvnw test package
```
Исправить ошибки компиляции/тестов если есть.

### Шаг B — SberbankScraper (3.2)
- Прочитать [`tools/coin-catalog-sberbank/scrape_sberbank_coins.py`](../tools/coin-catalog-sberbank/scrape_sberbank_coins.py)
- `SberbankApiClient` — GET витрина (cookies) + POST `/proxy/services/coin-catalog/coins` (4 металла) + GET buyout
- `SberbankResponseParser` — merge по id
- `SberbankScraper`
- Фикстуры JSON в `src/test/resources/fixtures/sberbank/`
- Без Playwright (как в docs §2)

### Шаг C — далее по таблице §6

Playwright dependency добавить в `pom.xml` начиная с vtb (3.3):
```xml
<dependency>
  <groupId>com.microsoft.playwright</groupId>
  <artifactId>playwright</artifactId>
  <version>...</version>
</dependency>
```

---

## 9. Структура проекта

```
mcp-server/
  pom.xml
  mvnw
  README.md
  docs/
    scraper-audit.md
    tools.md
    HANDOFF.md          # этот файл
  src/main/java/ru/scraper/coincatalog/
    CoinCatalogMcpApplication.java
    config/ScraperAutoConfiguration.java
    mcp/CoinCatalogToolSupport.java
    mcp/tools/CoinCatalogTools.java
    model/{Coin,ScrapeRequest,ScrapeResult,ScrapeStatus}.java
    scraper/
      CoinScraper.java, ScraperRegistry.java, NotImplementedScraper.java
      common/{HttpFetcher,PriceParser}.java
      zolotmd/            # готово
      sberbank/           # следующий
      ...
  src/main/resources/application.yml
  src/test/java/...
  src/test/resources/
    coins_catalog.schema.json
    fixtures/{bank}/...
```

---

## 10. Источники истины

| Документ | Назначение |
|----------|------------|
| [`docs/coin-catalog-scraper-requirements.md`](../docs/coin-catalog-scraper-requirements.md) | JSON, CLI, логика сбора, investment_only |
| [`src/test/resources/coins_catalog.schema.json`](../src/test/resources/coins_catalog.schema.json) | схема ответа MCP (camelCase) |
| [`docs/coin-catalog-skill-requirements.md`](../docs/coin-catalog-skill-requirements.md) | контракт tools для LLM |
| `tools/coin-catalog-*/tool.json` | параметры MCP tools |
| `tools/coin-catalog-*/scrape_*_coins.py` | эталон логики |

---

## 11. Критерии готовности проекта

- [ ] `./mvnw test` зелёный
- [ ] `java -jar target/coin-catalog-mcp-*.jar` — Cursor видит 8 tools
- [ ] Все 8 скраперов реализованы (не заглушки)
- [ ] Каждый банк: тесты + schema validation
- [ ] `docs/tools.md` — все tools «реализован»

---

## 12. Промпт для чистого контекста

Скопируй в новый чат:

```
Выполни план Java MCP-сервера для каталогов монет.

Прочитай handoff:
- mcp-server/docs/HANDOFF.md
- mcp-server/docs/scraper-audit.md
- mcp-server/docs/tools.md

Контекст:
- Проект mcp-server/ уже частично реализован (каркас MCP + zoloto-md скрапер)
- Python tools в tools/ НЕ изменять
- Стек: Spring Boot 3.5.7 + Spring AI 1.1.8, Java 21

Начни с:
1. ./mvnw test package — проверить сборку
2. Реализовать SberbankScraper (этап 3.2)
3. Далее скраперы по порядку из HANDOFF.md §6

Следуй архитектуре Parser/Fetcher/Scraper, тесты обязательны.
```
