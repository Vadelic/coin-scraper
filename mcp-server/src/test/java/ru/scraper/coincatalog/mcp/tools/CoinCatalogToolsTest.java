package ru.scraper.coincatalog.mcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.scraper.coincatalog.model.Coin;
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

    @MockitoBean
    private AurumexPlaywrightFetcher aurumexPlaywrightFetcher;

    @MockitoBean
    private LantaPlaywrightFetcher lantaPlaywrightFetcher;

    @MockitoBean
    private RshbPlaywrightFetcher rshbPlaywrightFetcher;

    @Test
    void rshbToolReturnsCoins() {
        when(rshbPlaywrightFetcher.fetchCatalog("золото", true, "77"))
                .thenReturn(new RshbPlaywrightFetcher.FetchResult(
                        1,
                        List.of(new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 85000.0, 107000.0))));

        List<Coin> coins = tools.scrapeRshb("золото", true, null);

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
        assertThat(coins.get(0).sellPrice()).isEqualTo(107000.0);
    }

    @Test
    void lantaToolReturnsCoins() {
        when(lantaPlaywrightFetcher.fetchCatalog("победоносец", true))
                .thenReturn(new LantaPlaywrightFetcher.FetchResult(
                        1,
                        List.of(new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 99700.0))));

        List<Coin> coins = tools.scrapeLanta("победоносец", true);

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).name()).contains("Победоносец");
        assertThat(coins.get(0).sellPrice()).isEqualTo(99700.0);
    }

    @Test
    void aurumexToolReturnsFilteredCoins() throws Exception {
        when(aurumexPlaywrightFetcher.fetchAllPages())
                .thenReturn(new AurumexPlaywrightFetcher.FetchResult(
                        1, List.of(loadAurumexFixture("payload_page1.json"))));

        List<Coin> coins = tools.scrapeAurumex("победоносец");

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
    }

    private static tools.jackson.databind.JsonNode loadAurumexFixture(String name) throws Exception {
        try (var in = CoinCatalogToolsTest.class.getResourceAsStream("/fixtures/aurumex/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return new tools.jackson.databind.ObjectMapper().readTree(text);
        }
    }
}
