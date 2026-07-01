package ru.scraper.coincatalog.scraper;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public class ScraperRegistry {

    private final Map<String, CoinScraper> scrapers;

    public ScraperRegistry(List<CoinScraper> scrapers) {
        this.scrapers = scrapers.stream()
                .collect(Collectors.toUnmodifiableMap(CoinScraper::slug, Function.identity()));
    }

    public CoinScraper get(String slug) {
        CoinScraper scraper = scrapers.get(slug);
        if (scraper == null) {
            throw new IllegalArgumentException("Unknown scraper slug: " + slug);
        }
        return scraper;
    }
}
