package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;

/**
 * Live smoke test for {@code lanta} (Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=LantaLiveTest}
 */
class LantaLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert("lanta", ScrapeRequest.of("победоносец", true, null));
    }
}
