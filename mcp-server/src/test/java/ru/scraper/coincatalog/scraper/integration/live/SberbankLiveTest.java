package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeStatus;
import ru.scraper.coincatalog.scraper.support.ScraperTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live HTTP smoke tests for {@code sberbank} (без Playwright).
 * Run: {@code RUN_LIVE_TESTS=true ./mvnw test -Dtest=SberbankLiveTest}
 */
class SberbankLiveTest extends AbstractLiveScraperTest {

    @Test
    void pobedonosetsInvestmentCatalog() throws Exception {
        scrapeAndAssert("sberbank", ScrapeRequest.of("победоносец", true, null));
    }

    @Test
    void georgiyQueryIncludesSilverPobedonosets() throws Exception {
        var result = registry.get("sberbank").scrape(ScrapeRequest.of("георгий победоносец", true, null));
        ScraperTestSupport.assertLiveResult(result, "sberbank");
        if (result.scrapeStatus() == ScrapeStatus.OK) {
            assertThat(result.coins().stream().anyMatch(c -> "Серебро".equals(c.metal()))).isTrue();
        }
    }
}
