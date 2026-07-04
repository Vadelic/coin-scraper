package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeSource;

/**
 * Live smoke test for {@code goldenplata} (Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=GoldenplataLiveTest}
 */
class GoldenplataLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert(ScrapeSource.GOLDENPLATA, ScrapeRequest.of("победоносец", true, null));
    }
}
