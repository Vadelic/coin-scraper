package ru.scraper.coincatalog.scraper.aurumex;

import tools.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.scraper.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.CoinScraper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AurumexScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(AurumexScraper.class);

    private final AurumexPlaywrightFetcher fetcher;

    public AurumexScraper(AurumexPlaywrightFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String slug() {
        return "aurumex";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("").strip();
            AurumexPlaywrightFetcher.FetchResult fetchResult = fetcher.fetchAllPages();
            List<Coin> coins = mergeAndFilter(fetchResult.stores(), query);

            if (fetchResult.pagesProcessed() == 0 && coins.isEmpty()) {
                return ScrapeResult.error(request, "Не удалось загрузить каталог (0 страниц payload)");
            }
            if (!query.isEmpty()) {
                log.info("После фильтра query=«{}»: {} монет", query, coins.size());
            }
            return ScrapeResult.ok(request, fetchResult.pagesProcessed(), coins);
        } catch (CaptchaBlockedException e) {
            log.error("Captcha blocked: {}", e.getMessage());
            return ScrapeResult.captchaBlocked(request, e.getMessage());
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage());
            return ScrapeResult.error(request, e.getMessage());
        }
    }

    private List<Coin> mergeAndFilter(List<JsonNode> stores, String query) {
        Map<String, Coin> byKey = new LinkedHashMap<>();
        for (JsonNode store : stores) {
            for (Coin coin : AurumexPayloadParser.extractCoinsFromStore(store)) {
                String key = AurumexPayloadParser.dedupeKey(coin);
                if (byKey.containsKey(key)) {
                    continue;
                }
                if (!AurumexPayloadParser.coinMatchesQuery(coin, query)) {
                    continue;
                }
                if (coin.sellPrice() == null) {
                    log.warn(
                            "Нет sell_price для «{}» (артикул {})",
                            coin.name(),
                            coin.catalogNumber());
                }
                byKey.put(key, coin);
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
