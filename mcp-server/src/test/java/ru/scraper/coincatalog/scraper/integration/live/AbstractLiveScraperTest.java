package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import ru.scraper.coincatalog.application.ScrapeRegistry;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeSource;
import ru.scraper.coincatalog.scraper.support.ScraperTestSupport;

@SpringBootTest
@Tag("live")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_TESTS", matches = "true")
abstract class AbstractLiveScraperTest {

    @Autowired
    protected ScrapeRegistry scrapeRegistry;

    protected void scrapeAndAssert(ScrapeSource source, ScrapeRequest request) throws Exception {
        ScraperTestSupport.assertLiveResult(scrapeRegistry.run(source, request), source);
    }
}
