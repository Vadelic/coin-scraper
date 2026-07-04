package ru.scraper.coincatalog.scraper;

import ru.scraper.coincatalog.scraper.common.ScrapePayload;

/** Скрапер одного источника каталога. */
public interface CoinScraper<T> {

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
