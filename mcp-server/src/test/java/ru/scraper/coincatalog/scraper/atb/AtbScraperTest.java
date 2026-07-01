package ru.scraper.coincatalog.scraper.atb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeStatus;
import ru.scraper.coincatalog.scraper.CaptchaBlockedException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AtbScraperTest {

    @Mock
    private AtbPlaywrightFetcher fetcher;

    @InjectMocks
    private AtbScraper scraper;

    @Test
    void returnsOkWithCoins() throws Exception {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new AtbPlaywrightFetcher.FetchResult(1, sampleCoins(), loadFixture("ajax_fragment.html")));

        var result = scraper.scrape(ScrapeRequest.of(null, true, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.totalCoins()).isEqualTo(2);
        assertThat(result.investmentOnly()).isTrue();
    }

    @Test
    void emptySearchResultIsOk() throws Exception {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new AtbPlaywrightFetcher.FetchResult(1, List.of(), loadFixture("ajax_empty.html")));

        var result = scraper.scrape(ScrapeRequest.of("нет такой", false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalCoins()).isZero();
    }

    @Test
    void emptyWithoutNoResultMarkerIsError() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new AtbPlaywrightFetcher.FetchResult(1, List.of(), "<div></div>"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("распарсить");
    }

    @Test
    void captchaBlockedReturnsStatus() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenThrow(new CaptchaBlockedException("CAPTCHA"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.CAPTCHA_BLOCKED);
    }

    private static List<Coin> sampleCoins() throws Exception {
        List<AtbPageParser.CardMatch> cards =
                AtbPageParser.parseCardsFromFragment(loadFixture("ajax_fragment.html"));
        return List.of(
                AtbPageParser.parseCard(
                                cards.get(0).cardHtml(),
                                cards.get(0).href(),
                                loadFixture("detail_georgiy.html"))
                        .orElseThrow()
                        .coin(),
                AtbPageParser.parseCard(
                                cards.get(1).cardHtml(),
                                cards.get(1).href(),
                                loadFixture("detail_silver.html"))
                        .orElseThrow()
                        .coin());
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = AtbScraperTest.class.getResourceAsStream("/fixtures/atb/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
