package ru.scraper.coincatalog.scraper.goldenplata;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenplataPageParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildCatalogUrlWithQueryAndPage() {
        String url = GoldenplataPageParser.buildCatalogUrl(GoldenplataPageParser.CATALOG_URL, 2, "золото");
        assertThat(url).contains("q=");
        assertThat(url).contains("PAGEN_4=2");
    }

    @Test
    void resolveCatalogBaseForInvestment() {
        assertThat(GoldenplataPageParser.resolveCatalogBase(true))
                .isEqualTo(GoldenplataPageParser.INVESTMENT_CATALOG_URL);
        assertThat(GoldenplataPageParser.resolveCatalogBase(false))
                .isEqualTo(GoldenplataPageParser.CATALOG_URL);
    }

    @Test
    void parseTotalPagesFromFixture() throws Exception {
        assertThat(GoldenplataPageParser.parseTotalPages(loadFixture("catalog_page1.html"))).isEqualTo(2);
    }

    @Test
    void parseCoinsFromHtmlFixture() throws Exception {
        List<Coin> coins = GoldenplataPageParser.parseCoinsFromHtml(loadFixture("catalog_page1.html"));

        assertThat(coins).hasSize(3);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
        assertThat(coins.get(0).name()).contains("Победоносец");
        assertThat(coins.get(0).metal()).isEqualTo("Золото");
        assertThat(coins.get(0).weightG()).isEqualTo(7.78);
        assertThat(coins.get(0).sellPrice()).isEqualTo(99700.0);
        assertThat(coins.get(1).metal()).isEqualTo("Серебро");
        assertThat(coins.get(2).sellPrice()).isNull();
    }

    @Test
    void analyticsPayloadUsesCardPriceFallback() throws Exception {
        String json =
                """
                {"item_name":"Тест","item_id":"1","item_variant":"золото","cardprice":"5000","availability":true}
                """;
        Coin coin = GoldenplataPageParser.analyticsPayloadToCoin(MAPPER.readTree(json)).orElseThrow();
        assertThat(coin.sellPrice()).isEqualTo(5000.0);
    }

    @Test
    void normalizeMetalFromName() {
        assertThat(GoldenplataPageParser.normalizeMetal("золото")).isEqualTo("Золото");
        assertThat(GoldenplataPageParser.normalizeMetal("монета палладий")).isEqualTo("Палладий");
    }

    @Test
    void dedupeKeyPrefersItemId() {
        Coin coin = new Coin("5216-0060", "Test", "Золото", 7.78, null, 100.0);
        assertThat(GoldenplataPageParser.dedupeKey(coin, "5216-0060")).isEqualTo("id:5216-0060");
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = GoldenplataPageParserTest.class.getResourceAsStream("/fixtures/goldenplata/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
