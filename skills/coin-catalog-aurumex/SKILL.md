---
name: coin-catalog-aurumex
description: Получает каталог монет банка Aurumex, читает JSON и формирует сводку и аналитику. Use when user asks about Aurumex coin catalog, summary, analytics.
disable-model-invocation: true
---

# Aurumex Coin Catalog

## Когда использовать
- Когда нужно получить каталог монет банка Aurumex.
- Когда нужно прочитать готовый JSON этого банка и вернуть сводку и аналитику.

## Порядок действий
1. Вызвать инструмент банка: `coin-catalog-aurumex`.
2. Убедиться, что JSON-файл существует: `tool/coin-catalog-aurumex/coins_aurumex_catalog.json`.
3. Прочитать JSON и посчитать сводку/аналитику.

## Что возвращать пользователю
- Сводка: банк, путь к JSON, количество монет.
- Аналитика:
  - минимальная и максимальная `buy_price` (если поле есть);
  - минимальная и максимальная `sell_price` (если поле есть);
  - медианная `sell_price` по валидным значениям;
  - топ категорий по `metal`;
  - доля записей с пропусками в `name`, `buy_price`, `sell_price`, `metal`.

## Правила устойчивости
- Числа цен очищать от символов валют и пробелов перед расчетами.
- Если поле отсутствует в данных банка, выводить `нет данных`.
- Если JSON пустой или файл отсутствует, вернуть пустую сводку без падения.

## Важные пути
- Tool: `coin-catalog-aurumex`
- Output JSON: `tool/coin-catalog-aurumex/coins_aurumex_catalog.json`
