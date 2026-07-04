package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeSource;

/**
 * Live smoke test for {@code aurumex} (Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=AurumexLiveTest}
 */
class AurumexLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsCatalog() throws Exception {
        scrapeAndAssert(ScrapeSource.AURUMEX, ScrapeRequest.of("победоносец", null, null));
    }
}
