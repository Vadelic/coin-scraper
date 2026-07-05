package ru.scraper.coincatalog.scraper;

import java.util.List;

/** Скрапер одного источника каталога. */
public interface CoinScraper<T> {

    /**
     * Промежуточный результат scraper'а до упаковки в {@link ru.scraper.coincatalog.model.ScrapeResult}.
     *
     * @param pagesProcessed сколько страниц или API-запросов обработано
     * @param coins распарсенные элементы каталога (может быть пустым при валидном «ничего не найдено»)
     */
    record ScrapePayload<U>(int pagesProcessed, List<U> coins) {}

    /**
     * Собирает каталог: загрузка, парсинг, фильтрация.
     *
     * @param query подстрока для локального фильтра; пустая — без фильтра
     * @param investmentOnly {@code true} — только инвестиционные монеты
     * @param region код региона; {@code null} — значение по умолчанию источника
     * @return число обработанных страниц/запросов и список элементов каталога
     */
    ScrapePayload<T> scrape(String query, boolean investmentOnly, String region);
}
