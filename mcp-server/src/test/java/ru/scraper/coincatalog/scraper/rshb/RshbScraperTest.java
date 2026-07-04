package ru.scraper.coincatalog.scraper.rshb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeStatus;
import ru.scraper.coincatalog.scraper.support.ScraperTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RshbScraperTest {

    @Mock
    private RshbPlaywrightFetcher fetcher;

    @InjectMocks
    private RshbScraper scraper;

    @Test
    void slugIsRshb() {
        assertThat(scraper.slug()).isEqualTo("rshb");
    }

    @Test
    void returnsOkWithCoins() throws Exception {
        when(fetcher.fetchCatalog(anyString(), anyBoolean(), eq("77")))
                .thenReturn(new RshbPlaywrightFetcher.FetchResult(
                        2,
                        List.of(new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 85000.0, 107000.0))));

        var result = scraper.scrape(ScrapeRequest.of("победоносец", true, "77"));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.totalCoins()).isEqualTo(1);
        assertThat(result.query()).isEqualTo("победоносец");
        assertThat(result.investmentOnly()).isTrue();
        ScraperTestSupport.assertOkWithCoins(result);
    }

    @Test
    void fetchFailureReturnsError() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean(), anyString()))
                .thenThrow(new IllegalStateException("network down"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("network down");
    }

    @Test
    void zeroPagesIsError() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean(), anyString()))
                .thenReturn(new RshbPlaywrightFetcher.FetchResult(0, List.of()));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("Не удалось загрузить каталог");
    }

    @Test
    void usesDefaultRegionWhenMissing() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean(), eq("77")))
                .thenReturn(new RshbPlaywrightFetcher.FetchResult(1, List.of()));

        scraper.scrape(ScrapeRequest.of(null, false, null));

        // verified by eq("77") in stub
    }
}
