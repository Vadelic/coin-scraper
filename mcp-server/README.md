# coin-catalog-mcp

Локальный MCP-сервер (stdio) для каталогов монет 8 банков. Стек: **Java 21**, **Spring Boot 3.5.x**, **Spring AI 1.1.x**.

Контракт ответа: [`docs/coins_catalog.schema.json`](../docs/coins_catalog.schema.json).  
Аудит Python-скраперов: [`docs/scraper-audit.md`](docs/scraper-audit.md).

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

## Тесты

```bash
mvn test
```

## Подключение к Cursor

Settings → MCP → добавить сервер:

```json
{
  "mcpServers": {
    "coin-catalog": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/Scraper/mcp-server/target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar"
      ]
    }
  }
}
```

## Tools

Полное описание: [docs/tools.md](docs/tools.md).

| Tool | Статус |
|------|--------|
| `coin-catalog-zoloto-md` | реализован |
| остальные 7 | заглушка (`not implemented`) |

## stdio

Логи пишутся в `logs/coin-catalog-mcp.log`. stdout занят MCP-протоколом — не добавляйте `System.out` в код.
