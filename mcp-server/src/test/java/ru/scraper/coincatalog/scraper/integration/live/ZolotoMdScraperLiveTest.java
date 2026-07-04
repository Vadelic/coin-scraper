package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.scraper.common.HttpFetcher;
import ru.scraper.coincatalog.scraper.zolotmd.ZolotoMdPageParser;
import ru.scraper.coincatalog.scraper.zolotmd.ZolotoMdScraper;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_TESTS", matches = "true")
class ZolotoMdScraperLiveTest {

    @Test
    void fetchAndParseInvestmentCatalog() {
        var scraper = new ZolotoMdScraper(HttpFetcher.defaults());
        var result = scraper.scrape("", true, null);

        assertThat(result.pagesProcessed()).isGreaterThan(0);
        assertThat(result.coins()).isNotEmpty();
        assertThat(result.coins().get(0).name()).isNotBlank();
    }

    @Test
    void parseLiveHtmlStructure() {
        String url = ZolotoMdPageParser.buildCatalogUrl(1, 100, "победоносец", true);
        String html = HttpFetcher.defaults().fetchText(url);
        var coins = ZolotoMdPageParser.parseCoins(html);

        assertThat(html).contains("js-product product-list_item");
        assertThat(coins).isNotEmpty();
    }
}
