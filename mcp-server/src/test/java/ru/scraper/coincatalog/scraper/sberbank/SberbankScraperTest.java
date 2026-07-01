package ru.scraper.coincatalog.scraper.sberbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeStatus;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SberbankScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private SberbankApiClient apiClient;

    @InjectMocks
    private SberbankScraper scraper;

    @Test
    void returnsOkWithCoinsFromMergedEntities() throws Exception {
        List<ObjectNode> entities = loadMergedEntities();
        when(apiClient.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new SberbankApiClient.FetchResult(4, entities));

        var result = scraper.scrape(ScrapeRequest.of("победоносец", true, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(4);
        assertThat(result.totalCoins()).isOne();
        assertThat(result.coins().get(0).catalogNumber()).isEqualTo("5216-0060");
        assertThat(result.coins().get(0).buyPrice()).isEqualTo(89500.0);
        assertThat(result.query()).isEqualTo("победоносец");
        assertThat(result.investmentOnly()).isTrue();
    }

    @Test
    void filtersByQueryLocally() throws Exception {
        List<ObjectNode> entities = loadMergedEntities();
        when(apiClient.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new SberbankApiClient.FetchResult(4, entities));

        var result = scraper.scrape(ScrapeRequest.of("николай", false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalCoins()).isOne();
        assertThat(result.coins().get(0).catalogNumber()).isEqualTo("5111-0008");
    }

    @Test
    void zeroPagesAndNoCoinsIsError() {
        when(apiClient.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new SberbankApiClient.FetchResult(0, List.of()));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("0 запросов");
    }

    @Test
    void fetchFailureReturnsError() {
        when(apiClient.fetchCatalog(anyString(), anyBoolean()))
                .thenThrow(new IllegalStateException("HTTP 403"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("HTTP 403");
    }

    @Test
    void deduplicatesByEntityId() throws Exception {
        ObjectNode duplicate = (ObjectNode) MAPPER.readTree("""
                {"id":"dup","name":"Same coin","catalogNumber":"X-1","price":100,"metal":"Золото"}
                """);
        when(apiClient.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new SberbankApiClient.FetchResult(4, List.of(duplicate, duplicate.deepCopy())));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.totalCoins()).isOne();
    }

    private static List<ObjectNode> loadMergedEntities() throws Exception {
        List<ObjectNode> gold = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_gold.json"));
        List<ObjectNode> silver = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_silver.json"));
        gold.get(0).put("metal", "Золото");
        silver.get(0).put("metal", "Серебро");
        List<JsonNode> buyout = SberbankResponseParser.entitiesFromBuyoutResponse(loadJson("buyout.json"));
        var catalog = new java.util.ArrayList<>(gold);
        catalog.addAll(silver);
        SberbankResponseParser.mergeBuyoutIntoCatalog(catalog, buyout);
        return catalog;
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (var in = SberbankScraperTest.class.getResourceAsStream("/fixtures/sberbank/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }
}
