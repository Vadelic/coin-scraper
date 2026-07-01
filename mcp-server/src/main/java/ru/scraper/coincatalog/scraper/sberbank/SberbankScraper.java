package ru.scraper.coincatalog.scraper.sberbank;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.scraper.CoinScraper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SberbankScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(SberbankScraper.class);

    private final SberbankApiClient apiClient;

    public SberbankScraper(SberbankApiClient apiClient) {
        this.apiClient = apiClient;
    }

    @Override
    public String slug() {
        return "sberbank";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("");
            boolean investmentOnly = request.investmentOnly().orElse(false);
            SberbankApiClient.FetchResult fetchResult = apiClient.fetchCatalog(query, investmentOnly);
            List<Coin> coins = toCoins(fetchResult.entities(), query);

            if (fetchResult.pagesProcessed() == 0 && coins.isEmpty()) {
                return ScrapeResult.error(request, "Не удалось загрузить каталог (0 запросов к API)");
            }
            return ScrapeResult.ok(request, fetchResult.pagesProcessed(), coins);
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage());
            return ScrapeResult.error(request, e.getMessage());
        }
    }

    private List<Coin> toCoins(List<ObjectNode> entities, String query) {
        List<Coin> coins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ObjectNode entity : entities) {
            var coinOpt = SberbankResponseParser.entityToCoin(entity);
            if (coinOpt.isEmpty()) {
                log.warn("Пропуск записи: нет id");
                continue;
            }
            Coin coin = coinOpt.get();
            if (coin.name() == null || coin.name().isBlank()) {
                log.warn("Пропуск записи: пустое name, catalog_number={}", coin.catalogNumber());
                continue;
            }
            if (!SberbankResponseParser.coinMatchesQuery(coin, query)) {
                continue;
            }
            String key = entity.hasNonNull("id") ? entity.get("id").asText() : SberbankResponseParser.dedupeKey(coin);
            if (!seen.add(key)) {
                log.warn(
                        "Дубликат (id {}): «{}», артикул={}",
                        key,
                        coin.name(),
                        coin.catalogNumber());
                continue;
            }
            if (coin.sellPrice() == null) {
                log.warn(
                        "Нет sell_price для «{}» (артикул {})",
                        coin.name(),
                        coin.catalogNumber());
            }
            coins.add(coin);
        }
        return coins;
    }
}
