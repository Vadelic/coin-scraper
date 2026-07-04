package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;

/**
 * Live smoke test for {@code goldenplata} (Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=GoldenplataLiveTest}
 */
class GoldenplataLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert("goldenplata", ScrapeRequest.of("победоносец", true, null));
    }
}
