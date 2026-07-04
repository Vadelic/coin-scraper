package ru.scraper.coincatalog.scraper.integration.live;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.scraper.common.HttpFetcher;
import ru.scraper.coincatalog.scraper.zolotmd.ZolotoMdCatalogFetcher;
import ru.scraper.coincatalog.scraper.zolotmd.ZolotoMdPageParser;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("live")
@EnabledIfEnvironmentVariable(named = "RUN_LIVE_TESTS", matches = "true")
class ZolotoMdFetcherLiveTest {

    @Test
    void fetchAndParseInvestmentCatalog() {
        var fetcher = new ZolotoMdCatalogFetcher(HttpFetcher.defaults());
        var result = fetcher.fetchAllPages("", true);

        assertThat(result.totalPages()).isGreaterThan(0);
        assertThat(result.coins()).isNotEmpty();
        assertThat(result.coins().get(0).coin().name()).isNotBlank();
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
