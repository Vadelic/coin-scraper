# coin-catalog-mcp

Локальный MCP-сервер для каталогов монет 8 банков. Стек: **Java 21**, **Spring Boot 4.1.x**, **Spring AI 2.0.x**.

Транспорт по умолчанию: **Streamable HTTP** (`http://localhost:8042/mcp`).  
Для Cursor subprocess: профиль **`stdio`** (см. ниже).

Контракт ответа MCP (camelCase): [`src/test/resources/coins_catalog.schema.json`](src/test/resources/coins_catalog.schema.json). Корневая Python-схема в `docs/` — snake_case, для MCP не используется.  
Аудит Python-скраперов: [`docs/scraper-audit.md`](docs/scraper-audit.md).  
Миграция на HTTP: [`docs/http-migration-spikes.md`](docs/http-migration-spikes.md).

Скраперы (кроме **Ланта**) используют `java.net.http.HttpClient` (`HttpScrapeClient`) — браузер не нужен.  
**Ланта** — Playwright: системный Chrome/Edge или путь через `--browser=`.

## Запуск (Streamable HTTP)

```bash
java -jar target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar
```

Lanta с корпоративным браузером и headful (для CAPTCHA):

```bash
java -jar target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar \
  --browser=/Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome \
  --lanta.headful=true
```

Сессия после успешного прохода сохраняется в `data/lanta-storage-state.json` (или `LANTA_STORAGE_STATE`).  
Без `--browser` Playwright пробует каналы chrome → msedge → chromium, затем bundled Chromium.

Эндпоинт MCP: `http://localhost:8042/mcp`

## Документация

- [HANDOFF.md](docs/HANDOFF.md) — **передача в чистый контекст** (статус, план, промпт)
- [tools.md](docs/tools.md) — описание всех MCP tools
- [scraper-audit.md](docs/scraper-audit.md) — аудит Python-скраперов

## Сборка

Требуется JDK 21 и Maven 3.9+.

```bash
cd mcp-server
mvn package
```

JAR: `target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar`

## Отладка через MCP Inspector

Требуется Node.js ^22.7.5. Сначала запустите сервер (см. выше), затем в **другом** терминале:

```bash
npx @modelcontextprotocol/inspector
```

Откроется веб-UI на `http://localhost:6274` (прокси — порт `6277`).

1. В поле транспорта выберите **Streamable HTTP**.
2. URL: `http://localhost:8042/mcp`
3. Нажмите **Connect** — в списке появятся 8 tools.

Проверка из CLI без браузера:

```bash
npx @modelcontextprotocol/inspector --cli http://localhost:8042/mcp --transport http --method tools/list
```

Для stdio-профиля (subprocess вместо HTTP):

```bash
npx @modelcontextprotocol/inspector java -jar target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar --spring.profiles.active=stdio
```

## Тесты

```bash
mvn test
```

## Подключение к Cursor

### Streamable HTTP (рекомендуется)

Сначала запустите сервер (см. выше), затем в Settings → MCP:

```json
{
  "mcpServers": {
    "coin-catalog": {
      "url": "http://localhost:8042/mcp"
    }
  }
}
```

### stdio (subprocess)

```json
{
  "mcpServers": {
    "coin-catalog": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/Scraper/mcp-server/target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=stdio"
      ]
    }
  }
}
```

При профиле `stdio` логи пишутся в `logs/coin-catalog-mcp.log`; stdout занят MCP-протоколом.

## Tools

Полное описание: [docs/tools.md](docs/tools.md).

| Tool | Статус |
|------|--------|
| `coin-catalog-zoloto-md` | реализован |
| остальные 7 | заглушка (`not implemented`) |
