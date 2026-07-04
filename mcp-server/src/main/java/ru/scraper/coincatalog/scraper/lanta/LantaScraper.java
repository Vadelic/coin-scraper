package ru.scraper.coincatalog.scraper.lanta;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.scraper.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.CoinScraper;

@Service
public class LantaScraper implements CoinScraper {

    private static final Logger log = LoggerFactory.getLogger(LantaScraper.class);

    private final LantaPlaywrightFetcher fetcher;

    public LantaScraper(LantaPlaywrightFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String slug() {
        return "lanta";
    }

    @Override
    public ScrapeResult scrape(ScrapeRequest request) {
        try {
            String query = request.query().orElse("").strip();
            boolean investmentOnly = request.investmentOnly().orElse(false);

            LantaPlaywrightFetcher.FetchResult fetchResult = fetcher.fetchCatalog(query, investmentOnly);
            if (fetchResult.pagesProcessed() == 0) {
                return ScrapeResult.error(request, "Не удалось загрузить каталог");
            }
            return ScrapeResult.ok(request, fetchResult.pagesProcessed(), fetchResult.coins());
        } catch (CaptchaBlockedException e) {
            log.error("Captcha blocked: {}", e.getMessage());
            return ScrapeResult.captchaBlocked(request, e.getMessage());
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage());
            return ScrapeResult.error(request, e.getMessage());
        }
    }
}
