package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;

/**
 * Live smoke test for {@code rshb} (Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=RshbLiveTest}
 */
class RshbLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalogMoscow() throws Exception {
        scrapeAndAssert("rshb", ScrapeRequest.of("победоносец", true, "77"));
    }
}
