package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeSource;

/**
 * Live smoke test for {@code rshb} (Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=RshbLiveTest}
 */
class RshbLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalogMoscow() throws Exception {
        scrapeAndAssert(ScrapeSource.RSHB, ScrapeRequest.of("победоносец", true, "77"));
    }
}
