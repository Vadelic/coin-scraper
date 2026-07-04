package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeSource;

/**
 * Live smoke test for {@code vtb} (Playwright + HTTP).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=VtbLiveTest}
 */
class VtbLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert(ScrapeSource.VTB, ScrapeRequest.of("победоносец", true, null));
    }
}
