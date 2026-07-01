package ru.scraper.coincatalog.scraper;

import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;

public interface CoinScraper {

    String slug();

    ScrapeResult scrape(ScrapeRequest request);
}
