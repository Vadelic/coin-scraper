package ru.scraper.coincatalog.scraper.aurumex;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AurumexScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private AurumexPlaywrightFetcher fetcher;

    @InjectMocks
    private AurumexScraper scraper;

    @Test
    void slugIsAurumex() {
        assertThat(scraper.slug()).isEqualTo("aurumex");
    }

    @Test
    void returnsOkWithCoinsFromPayload() throws Exception {
        when(fetcher.fetchAllPages())
                .thenReturn(new AurumexPlaywrightFetcher.FetchResult(1, List.of(loadJson("payload_page1.json"))));

        var result = scraper.scrape(ScrapeRequest.of("победоносец", null, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.totalCoins()).isOne();
        assertThat(result.coins().get(0).catalogNumber()).isEqualTo("5216-0060");
        assertThat(result.investmentOnly()).isNull();
        ScraperTestSupport.assertOkWithCoins(result);
    }

    @Test
    void filtersByQueryLocally() throws Exception {
        when(fetcher.fetchAllPages())
                .thenReturn(new AurumexPlaywrightFetcher.FetchResult(1, List.of(loadJson("payload_page1.json"))));

        var result = scraper.scrape(ScrapeRequest.of("серебр", null, null));

        assertThat(result.totalCoins()).isOne();
        assertThat(result.coins().get(0).catalogNumber()).isEqualTo("5111-0008");
    }

    @Test
    void mergesMultiplePagesWithoutDuplicates() throws Exception {
        when(fetcher.fetchAllPages())
                .thenReturn(new AurumexPlaywrightFetcher.FetchResult(
                        2, List.of(loadJson("payload_page1.json"), loadJson("payload_page2.json"))));

        var result = scraper.scrape(ScrapeRequest.of(null, null, null));

        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.totalCoins()).isEqualTo(3);
    }

    @Test
    void zeroPagesAndNoCoinsIsError() {
        when(fetcher.fetchAllPages())
                .thenReturn(new AurumexPlaywrightFetcher.FetchResult(0, List.of()));

        var result = scraper.scrape(ScrapeRequest.of(null, null, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("0 страниц payload");
    }

    @Test
    void fetchFailureReturnsError() {
        when(fetcher.fetchAllPages()).thenThrow(new IllegalStateException("network down"));

        var result = scraper.scrape(ScrapeRequest.of(null, null, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("network down");
    }

    @Test
    void captchaBlockedReturnsStatus() {
        when(fetcher.fetchAllPages()).thenThrow(new CaptchaBlockedException("CAPTCHA"));

        var result = scraper.scrape(ScrapeRequest.of(null, null, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.CAPTCHA_BLOCKED);
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (var in = AurumexScraperTest.class.getResourceAsStream("/fixtures/aurumex/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }
}
