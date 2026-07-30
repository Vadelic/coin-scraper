# coin-catalog-mcp

Локальный MCP-сервер для каталогов монет 8 банков. Стек: **Java 21**, **Spring Boot 4.1.x**, **Spring AI 2.0.x**.

Транспорт по умолчанию: **Streamable HTTP** (`http://localhost:8042/mcp`).  
Для Cursor subprocess: профиль **`stdio`** (см. ниже).

Контракт ответа MCP (camelCase): [`src/test/resources/coins_catalog.schema.json`](src/test/resources/coins_catalog.schema.json). Корневая Python-схема в `docs/` — snake_case, для MCP не используется.  
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

## Запуск (Streamable HTTP)

```bash
java -jar target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar
```

Эндпоинт MCP: `http://localhost:8042/mcp`

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

### Параметр `--browser`

Если системный Chrome/Edge недоступен или нельзя скачать bundled Chromium Playwright, укажите путь к любому Chromium-бинарнику (SberBrowser, Chrome, Chromium и т.п.):

```bash
java -jar target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar --browser=<путь>
```

Без `--browser` сервер пробует каналы `chrome` → `msedge` → `chromium`, затем bundled.

#### Типичные пути

| ОС | Браузер | Путь к бинарнику |
|----|---------|------------------|
| Windows | SberBrowser | `%LOCALAPPDATA%\SberBrowser\Application\sberbrowser.exe` |
| Windows | Google Chrome | `C:\Program Files\Google\Chrome\Application\chrome.exe` |
| Windows | Microsoft Edge | `C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe` |
| macOS | Google Chrome | `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome` |
| macOS | Microsoft Edge | `/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge` |
| macOS | Chromium | `/Applications/Chromium.app/Contents/MacOS/Chromium` |
| Linux | Google Chrome | `/usr/bin/google-chrome` или `/usr/bin/google-chrome-stable` |
| Linux | Chromium | `/usr/bin/chromium` или `/usr/bin/chromium-browser` |
| Linux | Microsoft Edge | `/usr/bin/microsoft-edge` |

На Windows путь SberBrowser можно уточнить:

```bat
dir /s /b "%LOCALAPPDATA%\SberBrowser\Application\sberbrowser.exe"
```

#### Примеры запуска (CLI)

**Windows (cmd):**

```bat
java -jar target\coin-catalog-mcp-0.1.0-SNAPSHOT.jar --spring.profiles.active=stdio --browser=%LOCALAPPDATA%\SberBrowser\Application\sberbrowser.exe
```

**Windows (PowerShell):**

```powershell
java -jar target\coin-catalog-mcp-0.1.0-SNAPSHOT.jar `
  --spring.profiles.active=stdio `
  --browser="$env:LOCALAPPDATA\SberBrowser\Application\sberbrowser.exe"
```

**macOS:**

```bash
java -jar target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=stdio \
  --browser="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
```

**Linux:**

```bash
java -jar target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar \
  --spring.profiles.active=stdio \
  --browser=/usr/bin/google-chrome-stable
```

#### Примеры в Cursor (`mcp.json`)

**Windows + SberBrowser:**

```json
{
  "mcpServers": {
    "coin-catalog": {
      "command": "java",
      "args": [
        "-jar",
        "C:\\absolute\\path\\to\\Scraper\\mcp-server\\target\\coin-catalog-mcp-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=stdio",
        "--browser=C:\\Users\\user\\AppData\\Local\\SberBrowser\\Application\\sberbrowser.exe"
      ]
    }
  }
}
```

В JSON экранируйте обратные слэши (`\\`) или подставьте свой `%USERNAME%` вручную в полный путь.

**macOS:**

```json
{
  "mcpServers": {
    "coin-catalog": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/Scraper/mcp-server/target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=stdio",
        "--browser=/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
      ]
    }
  }
}
```

**Linux:**

```json
{
  "mcpServers": {
    "coin-catalog": {
      "command": "java",
      "args": [
        "-jar",
        "/absolute/path/to/Scraper/mcp-server/target/coin-catalog-mcp-0.1.0-SNAPSHOT.jar",
        "--spring.profiles.active=stdio",
        "--browser=/usr/bin/chromium-browser"
      ]
    }
  }
}
```

Для Streamable HTTP тот же `--browser` добавляется в командную строку при запуске JAR (без профиля `stdio`).


## Tools

Полное описание: [docs/tools.md](docs/tools.md).

| Tool | Статус |
|------|--------|
| `coin-catalog-zoloto-md` | реализован |
| остальные 7 | заглушка (`not implemented`) |
