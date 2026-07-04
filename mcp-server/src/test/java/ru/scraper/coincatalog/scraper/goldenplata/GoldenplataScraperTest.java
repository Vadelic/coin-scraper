package ru.scraper.coincatalog.scraper.goldenplata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeStatus;
import ru.scraper.coincatalog.scraper.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.support.ScraperTestSupport;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoldenplataScraperTest {

    @Mock
    private GoldenplataPlaywrightFetcher fetcher;

    @InjectMocks
    private GoldenplataScraper scraper;

    @Test
    void slugIsGoldenplata() {
        assertThat(scraper.slug()).isEqualTo("goldenplata");
    }

    @Test
    void returnsOkWithCoinsFromPages() throws Exception {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new GoldenplataPlaywrightFetcher.FetchResult(
                        2,
                        List.of(loadFixture("catalog_page1.html"), loadFixture("catalog_page2.html"))));

        var result = scraper.scrape(ScrapeRequest.of(null, true, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.totalCoins()).isEqualTo(4);
        assertThat(result.investmentOnly()).isTrue();
        ScraperTestSupport.assertOkWithCoins(result);
    }

    @Test
    void fetchFailureReturnsError() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("network down"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("network down");
    }

    @Test
    void deduplicatesAcrossPages() throws Exception {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new GoldenplataPlaywrightFetcher.FetchResult(
                        2,
                        List.of(loadFixture("catalog_page1.html"), loadFixture("catalog_page1.html"))));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.totalCoins()).isEqualTo(3);
    }

    @Test
    void zeroPagesIsError() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new GoldenplataPlaywrightFetcher.FetchResult(0, List.of()));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("0 страниц");
    }

    @Test
    void captchaBlockedReturnsStatus() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenThrow(new CaptchaBlockedException("CAPTCHA"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.CAPTCHA_BLOCKED);
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = GoldenplataScraperTest.class.getResourceAsStream("/fixtures/goldenplata/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
