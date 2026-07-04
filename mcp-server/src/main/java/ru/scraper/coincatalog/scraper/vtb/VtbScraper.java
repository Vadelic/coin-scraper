package ru.scraper.coincatalog.scraper.vtb;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class VtbScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(VtbScraper.class);

    private final VtbPlaywrightFetcher fetcher;

    public VtbScraper(VtbPlaywrightFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String slug() {
        return "vtb";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("");
            boolean investmentOnly = request.investmentOnly().orElse(false);
            if (!query.isBlank()) {
                log.info("Фильтр query=«{}» (локально по name / catalog_number / metal)", query);
            }

            VtbPlaywrightFetcher.FetchResult fetchResult = fetcher.fetchAllPages(investmentOnly);
            List<Coin> coins = toCoins(fetchResult.rows(), query);

            if (fetchResult.pagesProcessed() == 0 && coins.isEmpty()) {
                return ScrapeResult.error(request, "Не удалось загрузить каталог (0 страниц BFF)");
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

    private List<Coin> toCoins(List<JsonNode> rows, String query) {
        List<Coin> coins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            var coinOpt = VtbBffResponseParser.rowToCoin(row);
            if (coinOpt.isEmpty()) {
                log.warn("Пропуск записи: нет name и article");
                continue;
            }
            Coin coin = coinOpt.get();
            if (coin.name() == null || coin.name().isBlank()) {
                log.warn("Пропуск записи: пустое name, article={}", coin.catalogNumber());
                continue;
            }
            if (!VtbBffResponseParser.coinMatchesQuery(coin, query)) {
                continue;
            }
            String key = VtbBffResponseParser.dedupeKey(row, coin);
            if (key == null || key.isBlank()) {
                continue;
            }
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
