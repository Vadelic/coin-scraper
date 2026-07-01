package ru.scraper.coincatalog.scraper.rshb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.scraper.CoinScraper;

public class RshbScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(RshbScraper.class);

    private final RshbPlaywrightFetcher fetcher;

    public RshbScraper(RshbPlaywrightFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String slug() {
        return "rshb";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("").strip();
            boolean investmentOnly = request.investmentOnly().orElse(false);
            String region = request.region().orElse(RshbPageParser.DEFAULT_REGION_CODE);

            RshbPlaywrightFetcher.FetchResult fetchResult =
                    fetcher.fetchCatalog(query, investmentOnly, region);
            if (fetchResult.pagesProcessed() == 0) {
                return ScrapeResult.error(request, "Не удалось загрузить каталог");
            }
            return ScrapeResult.ok(request, fetchResult.pagesProcessed(), fetchResult.coins());
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage());
            return ScrapeResult.error(request, e.getMessage());
        }
    }
}
