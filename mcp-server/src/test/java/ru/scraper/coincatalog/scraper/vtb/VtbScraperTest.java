package ru.scraper.coincatalog.scraper.vtb;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VtbScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private VtbPlaywrightFetcher fetcher;

    @InjectMocks
    private VtbScraper scraper;

    @Test
    void slugIsVtb() {
        assertThat(scraper.slug()).isEqualTo("vtb");
    }

    @Test
    void returnsOkWithCoinsFromFetcher() throws Exception {
        when(fetcher.fetchAllPages(anyBoolean()))
                .thenReturn(new VtbPlaywrightFetcher.FetchResult(1, loadAllRows()));

        var result = scraper.scrape(ScrapeRequest.of("победоносец", true, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.totalCoins()).isOne();
        assertThat(result.coins().get(0).catalogNumber()).isEqualTo("5216-0060");
        assertThat(result.investmentOnly()).isTrue();
        ScraperTestSupport.assertOkWithCoins(result);
    }

    @Test
    void filtersByQueryLocally() throws Exception {
        when(fetcher.fetchAllPages(anyBoolean()))
                .thenReturn(new VtbPlaywrightFetcher.FetchResult(1, loadAllRows()));

        var result = scraper.scrape(ScrapeRequest.of("николай", false, null));

        assertThat(result.totalCoins()).isOne();
        assertThat(result.coins().get(0).catalogNumber()).isEqualTo("5111-0008");
    }

    @Test
    void zeroPagesAndNoCoinsIsError() {
        when(fetcher.fetchAllPages(anyBoolean()))
                .thenReturn(new VtbPlaywrightFetcher.FetchResult(0, List.of()));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("0 страниц BFF");
    }

    @Test
    void captchaBlockedReturnsStatus() {
        when(fetcher.fetchAllPages(anyBoolean()))
                .thenThrow(new CaptchaBlockedException("captcha page"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.CAPTCHA_BLOCKED);
        assertThat(result.error()).contains("captcha");
    }

    @Test
    void deduplicatesById() throws Exception {
        List<JsonNode> rows = loadAllRows();
        List<JsonNode> duplicated = new ArrayList<>(rows);
        duplicated.add(rows.get(0));
        when(fetcher.fetchAllPages(anyBoolean()))
                .thenReturn(new VtbPlaywrightFetcher.FetchResult(1, duplicated));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.totalCoins()).isEqualTo(2);
    }

    private static List<JsonNode> loadAllRows() throws Exception {
        return VtbBffResponseParser.parseListResponse(loadJson("list_page1.json")).coins();
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (var in = VtbScraperTest.class.getResourceAsStream("/fixtures/vtb/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }
}
