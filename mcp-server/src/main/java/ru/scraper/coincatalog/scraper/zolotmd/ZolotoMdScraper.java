package ru.scraper.coincatalog.scraper.zolotmd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.scraper.CoinScraper;

import java.util.List;

public class ZolotoMdScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(ZolotoMdScraper.class);

    private final ZolotoMdCatalogFetcher fetcher;

    public ZolotoMdScraper(ZolotoMdCatalogFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String slug() {
        return "zoloto-md";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("");
            boolean investmentOnly = request.investmentOnly().orElse(false);
            var result = fetcher.fetchAllPages(query, investmentOnly);
            List<Coin> coins = result.coins().stream().map(ZolotoMdPageParser.ParsedCoin::coin).toList();

            if (coins.isEmpty() && query.isBlank()) {
                return ScrapeResult.error(request, "Каталог пуст");
            }
            return ScrapeResult.ok(request, result.totalPages(), coins);
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage());
            return ScrapeResult.error(request, e.getMessage());
        }
    }
}
