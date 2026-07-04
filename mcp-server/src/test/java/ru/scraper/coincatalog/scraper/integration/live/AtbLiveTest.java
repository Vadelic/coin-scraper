package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;

/**
 * Live smoke test for {@code atb} (Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=AtbLiveTest}
 */
class AtbLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert("atb", ScrapeRequest.of("победоносец", true, null));
    }
}
