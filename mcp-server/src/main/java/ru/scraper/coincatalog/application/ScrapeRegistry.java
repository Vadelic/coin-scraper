package ru.scraper.coincatalog.application;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.model.ScrapeSource;
import ru.scraper.coincatalog.scraper.CoinScraper;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

/** Реестр {@link CoinScraper} по {@link ScrapeSource} и запуск scrape. */
@Slf4j
@Service
public class ScrapeRegistry {

    private static final String EMPTY_CATALOG_MESSAGE = "Не удалось загрузить каталог";
    private static final Logger log = LoggerFactory.getLogger(ScrapeRegistry.class);

    private final EnumMap<ScrapeSource, CoinScraper<Coin>> scrapers;

    @Autowired
    public ScrapeRegistry(List<CoinScraper<Coin>> scrapers, ApplicationContext applicationContext) {
        EnumMap<ScrapeSource, CoinScraper<Coin>> registry = new EnumMap<>(ScrapeSource.class);
        for (CoinScraper<Coin> scraper : scrapers) {
            ScrapeSource source = ScrapeSource.valueOf(resolveBeanName(scraper, applicationContext));
            if (registry.put(source, scraper) != null) {
                throw new IllegalStateException("Duplicate scraper for source: " + source);
            }
        }
        this.scrapers = registry;
    }

    /** Запускает scraper по источнику из реестра. */
    public ScrapeResult<Coin> run(ScrapeSource source, ScrapeRequest request) {
        CoinScraper<Coin> coinScraper = scrapers.get(source);
        if (coinScraper == null) {
            return ScrapeResult.notImplemented(request);
        }
        return run(coinScraper, request);
    }

    ScrapeResult<Coin> run(CoinScraper<Coin> coinScraper, ScrapeRequest request) {
        try {
            String query = request.query().orElse("").strip();
            boolean investmentOnly = request.investmentOnly().orElse(false);
            String region = request.region().orElse(null);

            var payload = coinScraper.scrape(query, investmentOnly, region);
            if (payload.pagesProcessed() == 0 && payload.coins().isEmpty()) {
                return ScrapeResult.error(request, EMPTY_CATALOG_MESSAGE);
            }
            return ScrapeResult.ok(request, payload.pagesProcessed(), payload.coins());
        } catch (CaptchaBlockedException e) {
            log.error("Captcha blocked: {}", e.getMessage());
            return ScrapeResult.captchaBlocked(request, e.getMessage());
        } catch (Exception e) {
            log.error("Scrape failed: {}", e.getMessage());
            return ScrapeResult.error(request, e.getMessage());
        }
    }

    private static String resolveBeanName(CoinScraper<Coin> scraper, ApplicationContext applicationContext) {
        Class<?> targetClass = AopUtils.getTargetClass(scraper);
        return Arrays.stream(applicationContext.getBeanNamesForType(targetClass))
                .filter(name -> applicationContext.getBean(name) == scraper)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Bean name not found for scraper: " + targetClass.getName()));
    }
}
