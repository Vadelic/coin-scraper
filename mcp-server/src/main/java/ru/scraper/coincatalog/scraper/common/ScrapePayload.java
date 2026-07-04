package ru.scraper.coincatalog.scraper.common;

import java.util.List;

/**
 * Промежуточный результат scraper'а до упаковки в {@link ru.scraper.coincatalog.model.ScrapeResult}.
 *
 * @param pagesProcessed сколько страниц или API-запросов обработано
 * @param coins распарсенные элементы каталога (может быть пустым при валидном «ничего не найдено»)
 */
public record ScrapePayload<T>(int pagesProcessed, List<T> coins) {}
