package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;

/**
 * Live smoke test for {@code zoloto-md} (HTTP, без Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=ZolotoMdLiveTest}
 */
class ZolotoMdLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert("zoloto-md", ScrapeRequest.of("победоносец", true, null));
    }
}
