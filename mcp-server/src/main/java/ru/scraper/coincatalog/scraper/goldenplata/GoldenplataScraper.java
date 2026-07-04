package ru.scraper.coincatalog.scraper.goldenplata;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class GoldenplataScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(GoldenplataScraper.class);

    private final GoldenplataPlaywrightFetcher fetcher;

    public GoldenplataScraper(GoldenplataPlaywrightFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String slug() {
        return "goldenplata";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("").strip();
            boolean investmentOnly = request.investmentOnly().orElse(false);

            GoldenplataPlaywrightFetcher.FetchResult fetchResult =
                    fetcher.fetchCatalog(query, investmentOnly);
            List<Coin> coins = mergeCoins(fetchResult.pageHtmls());

            if (fetchResult.pagesProcessed() == 0 && coins.isEmpty()) {
                return ScrapeResult.error(request, "Не удалось загрузить каталог (0 страниц)");
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

    private List<Coin> mergeCoins(List<String> pageHtmls) {
        Map<String, Coin> byKey = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        for (String html : pageHtmls) {
            for (Coin coin : GoldenplataPageParser.parseCoinsFromHtml(html)) {
                String key = GoldenplataPageParser.dedupeKey(coin, coin.catalogNumber());
                if (!seen.add(key)) {
                    log.warn(
                            "Дубликат (ключ {}): «{}», id={}",
                            key,
                            coin.name(),
                            coin.catalogNumber());
                    continue;
                }
                if (coin.sellPrice() == null) {
                    log.warn("Нет цены продажи: «{}»", coin.name());
                }
                byKey.put(key, coin);
            }
        }
        return new ArrayList<>(byKey.values());
    }
}
