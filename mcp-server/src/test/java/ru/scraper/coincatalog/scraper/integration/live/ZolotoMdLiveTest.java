package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeSource;

/**
 * Live smoke test for {@code zoloto-md} (HTTP, без Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=ZolotoMdLiveTest}
 */
class ZolotoMdLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert(ScrapeSource.ZOLOTO_MD, ScrapeRequest.of("победоносец", true, null));
    }
}
