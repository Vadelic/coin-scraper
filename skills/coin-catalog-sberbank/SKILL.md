---
name: coin-catalog-sberbank
description: Каталог инвестиционных монет Сбербанка (sberbank.ru); по умолчанию investment_only. Полный каталог — только если пользователь явно просит все монеты. Use when user asks about Сбербанк, Sberbank coins, find a coin at Sberbank, catalog summary.
disable-model-invocation: true
---

# Сбербанк Coin Catalog

## Когда использовать

- Нужен каталог монет банка Сбербанк (sberbank.ru/ru/person/mon).
- Нужно **найти монету** в этом банке (по названию, артикулу, металлу, весу, цене и т.п.).
- Нужна сводка или аналитика по каталогу Сбера.

Данные только из tool `coin-catalog-sberbank`, не из памяти модели. Сбор — через HTTP API (не Playwright).

## Порядок действий

1. Вызвать `coin-catalog-sberbank`.
   - **По умолчанию всегда** передавать `investment_only` (`--investment-only`, в POST `sections=["Инвестиционные монеты"]`) — только инвестиционные монеты.
   - **Исключение:** пользователь явно просит **весь** каталог, памятные/коллекционные монеты или «все монеты» — тогда `investment_only` **не** передавать.
   - Поиск — параметр `query` (`--query "…"`): строка в поле поиска API (сочетается с `investment_only`).
2. Распарсить единственный JSON из stdout (stderr — логи, не парсить).
3. Если `scrape_status` ≠ `ok` — сообщить статус и текст `error`, не выдавать цены как актуальные.
4. Дальше — поиск монеты и/или сводка (см. ниже).

## Поиск монеты

1. Сформировать `query` из запроса: название («Георгий Победоносец», «победон»), металл («золото»), артикул (`5216-0060`) и т.д.
2. Вызвать tool с `investment_only`, если не запрошен полный каталог; с `query`, если поиск узкий; для обзора — без `query`, затем отфильтровать локально по `coins[]`.
3. В `coins[]` оставить записи по смыслу: `name`, `catalog_number`, `metal`, `weight_g`, `buy_price`, `sell_price`.
4. Пользователю: для каждой подходящей монеты — `name`, `catalog_number`, `metal`, `weight_g`, `buy_price`, `sell_price` (₽; `null` → «нет данных»).
5. Если совпадений нет — «не найдено» + что искали.

## Сводка и аналитика

При запросе на обзор каталога:

- банк: Сбербанк, `scrape_status`, `total_pages`, `total_coins`, при наличии — `query`, `investment_only`;
- min/max `buy_price`, min/max и медиана `sell_price` по валидным значениям;
- топ по `metal`;
- доля пропусков в `name`, `buy_price`, `sell_price`, `metal`.

## Правила устойчивости

- Не читать JSON-файлы на диске — только stdout tool.
- Числа цен брать из JSON; `sell_price` — поле `price` из API, `buy_price` — `priceBuy` (в т.ч. после GET buyout).
- Отсутствующее поле — «нет данных».
- Пустой `coins` или ошибка сбора — ответ без падения, с объяснением.
- Не вызывать tool без `investment_only`, если пользователь не просил явно весь каталог.

## Важные пути

- Tool: `coin-catalog-sberbank`
