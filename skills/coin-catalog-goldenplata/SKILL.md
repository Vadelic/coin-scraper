---
name: coin-catalog-goldenplata
description: Получает каталог монет банка Золотая плата, читает JSON и формирует сводку и аналитику. Use when user asks about GoldenPlata coin catalog, summary, analytics.
disable-model-invocation: true
---

# Золотая плата Coin Catalog

## Когда использовать
- Когда нужно получить каталог монет банкаЗолотая плата.
- Когда нужно прочитать готовый JSON этого банка и вернуть сводку и аналитику.

## Порядок действий
1. Вызвать инструмент банка: `coin-catalog-goldenplata`.
2. Убедиться, что JSON-файл существует: `tools/coin-catalog-goldenplata/coins_goldenplata_catalog.json`.
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
- Tool: `coin-catalog-goldenplata`
- Output JSON: `tools/coin-catalog-goldenplata/coins_goldenplata_catalog.json`
