package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.scraper.ScraperRegistry;
import ru.scraper.coincatalog.scraper.support.ScraperTestSupport;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_TESTS", matches = "true")
abstract class AbstractLiveScraperTest {

    @Autowired
    protected ScraperRegistry registry;

    protected void scrapeAndAssert(String slug, ScrapeRequest request) throws Exception {
        ScraperTestSupport.assertLiveResult(registry.get(slug).scrape(request), slug);
    }
}
