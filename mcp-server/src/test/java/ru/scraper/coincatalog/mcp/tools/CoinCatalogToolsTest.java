package ru.scraper.coincatalog.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import ru.scraper.coincatalog.scraper.aurumex.AurumexPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.lanta.LantaPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.rshb.RshbPlaywrightFetcher;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
class CoinCatalogToolsTest {

    @Autowired
    private CoinCatalogTools tools;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AurumexPlaywrightFetcher aurumexPlaywrightFetcher;

    @MockBean
    private LantaPlaywrightFetcher lantaPlaywrightFetcher;

    @MockBean
    private RshbPlaywrightFetcher rshbPlaywrightFetcher;

    @Test
    void rshbToolUsesRegionAndReturnsOk() throws Exception {
        when(rshbPlaywrightFetcher.fetchCatalog("золото", true, "77"))
                .thenReturn(new RshbPlaywrightFetcher.FetchResult(
                        1,
                        List.of(new ru.scraper.coincatalog.model.Coin(
                                "5216-0060", "Георгий Победоносец", "Золото", 7.78, 85000.0, 107000.0))));

        String json = tools.scrapeRshb("золото", true, null);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("scrape_status").asText()).isEqualTo("ok");
        assertThat(root.get("query").asText()).isEqualTo("золото");
        assertThat(root.get("investment_only").asBoolean()).isTrue();
        assertThat(root.get("total_coins").asInt()).isEqualTo(1);
    }

    @Test
    void lantaToolPassesQuery() throws Exception {
        when(lantaPlaywrightFetcher.fetchCatalog("победоносец", true))
                .thenReturn(new LantaPlaywrightFetcher.FetchResult(
                        1,
                        List.of(new ru.scraper.coincatalog.model.Coin(
                                "5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 99700.0))));

        String json = tools.scrapeLanta("победоносец", true);
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.get("scrape_status").asText()).isEqualTo("ok");
        assertThat(root.get("query").asText()).isEqualTo("победоносец");
        assertThat(root.get("investment_only").asBoolean()).isTrue();
        assertThat(root.get("total_coins").asInt()).isEqualTo(1);
    }

    @Test
    void aurumexToolHasNoInvestmentOnlyField() throws Exception {
        when(aurumexPlaywrightFetcher.fetchAllPages())
                .thenReturn(new AurumexPlaywrightFetcher.FetchResult(
                        1, List.of(loadAurumexFixture("payload_page1.json"))));

        String json = tools.scrapeAurumex("золото");
        JsonNode root = objectMapper.readTree(json);

        assertThat(root.has("investment_only")).isFalse();
        assertThat(root.get("query").asText()).isEqualTo("золото");
        assertThat(root.get("scrape_status").asText()).isEqualTo("ok");
    }

    private static com.fasterxml.jackson.databind.JsonNode loadAurumexFixture(String name) throws Exception {
        try (var in = CoinCatalogToolsTest.class.getResourceAsStream("/fixtures/aurumex/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return new ObjectMapper().readTree(text);
        }
    }
}
