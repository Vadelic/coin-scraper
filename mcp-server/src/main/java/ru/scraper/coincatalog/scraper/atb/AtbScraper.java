package ru.scraper.coincatalog.scraper.atb;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.scraper.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.CoinScraper;

import java.util.List;

@Service
public class AtbScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(AtbScraper.class);

    private final AtbPlaywrightFetcher fetcher;

    public AtbScraper(AtbPlaywrightFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String slug() {
        return "atb";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("").strip();
            boolean investmentOnly = request.investmentOnly().orElse(false);

            AtbPlaywrightFetcher.FetchResult fetchResult = fetcher.fetchCatalog(query, investmentOnly);
            List<Coin> coins = fetchResult.coins();

            if (coins.isEmpty()) {
                if (AtbPageParser.isEmptySearchResult(fetchResult.fragmentHtml())) {
                    log.info("По запросу монет не найдено");
                    return ScrapeResult.ok(request, fetchResult.pagesProcessed(), List.of());
                }
                return ScrapeResult.error(request, "Не удалось распарсить монеты из ответа");
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
}
