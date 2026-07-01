package ru.scraper.coincatalog.scraper.zolotmd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZolotoMdScraperTest {

    @Mock
    private ZolotoMdCatalogFetcher fetcher;

    @InjectMocks
    private ZolotoMdScraper scraper;

    @Test
    void returnsOkWithCoins() {
        var coin = new ru.scraper.coincatalog.model.Coin(
                "5216-0060", "Георгий Победоносец", "Золото", 7.78, 89500.0, 99700.0);
        when(fetcher.fetchAllPages(anyString(), anyBoolean()))
                .thenReturn(new ZolotoMdCatalogFetcher.FetchResult(
                        1, List.of(new ZolotoMdPageParser.ParsedCoin(coin, "https://example/item"))));

        var result = scraper.scrape(ScrapeRequest.of("победоносец", true, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalCoins()).isOne();
        assertThat(result.query()).isEqualTo("победоносец");
        assertThat(result.investmentOnly()).isTrue();
    }

    @Test
    void emptyCatalogWithoutQueryIsError() {
        when(fetcher.fetchAllPages(anyString(), anyBoolean()))
                .thenReturn(new ZolotoMdCatalogFetcher.FetchResult(1, List.of()));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).isEqualTo("Каталог пуст");
    }

    @Test
    void fetchFailureReturnsError() {
        when(fetcher.fetchAllPages(anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("network down"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("network down");
    }
}
