package ru.scraper.coincatalog.scraper;

import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;

public class NotImplementedScraper implements CoinScraper {

    private final String slug;

    public NotImplementedScraper(String slug) {
        this.slug = slug;
    }

    @Override
    public String slug() {
        return slug;
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        return ScrapeResult.notImplemented(request);
    }
}
