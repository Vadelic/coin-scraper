package ru.scraper.coincatalog.scraper.vtb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class VtbBffResponseParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildListUrlIncludesPage() {
        assertThat(VtbBffResponseParser.buildListUrl(2))
                .isEqualTo("https://www.vtb.ru/api/bff/api/v1/coin/list?page=2");
    }

    @Test
    void buildInvestmentFilters() {
        List<ObjectNode> filters = VtbBffResponseParser.buildInvestmentFilters();
        assertThat(filters).hasSize(1);
        assertThat(filters.get(0).get("id").asText()).isEqualTo("coinKind");
        assertThat(filters.get(0).get("values").get(0).asText()).isEqualTo("Инвестиционные");
    }

    @Test
    void resolveApiFiltersForInvestmentOnly() {
        assertThat(VtbBffResponseParser.resolveApiFilters(true)).hasSize(1);
        assertThat(VtbBffResponseParser.resolveApiFilters(false)).isEmpty();
    }

    @Test
    void parseListResponseFromFixture() throws Exception {
        JsonNode body = loadJson("list_page1.json");
        var parsed = VtbBffResponseParser.parseListResponse(body);

        assertThat(parsed.coins()).hasSize(2);
        assertThat(parsed.maxPage()).isEqualTo(1);
    }

    @Test
    void rowToCoinMapsFields() throws Exception {
        JsonNode row = VtbBffResponseParser.parseListResponse(loadJson("list_page1.json")).coins().get(0);
        Coin coin = VtbBffResponseParser.rowToCoin(row).orElseThrow();

        assertThat(coin.catalogNumber()).isEqualTo("5216-0060");
        assertThat(coin.name()).contains("Победоносец");
        assertThat(coin.metal()).isEqualTo("Золото");
        assertThat(coin.weightG()).isEqualTo(7.78);
        assertThat(coin.sellPrice()).isEqualTo(99700.0);
        assertThat(coin.buyPrice()).isNull();
    }

    @Test
    void normalizeMetalExtractsPrefix() {
        assertThat(VtbBffResponseParser.normalizeMetal("золото 999")).isEqualTo("Золото");
        assertThat(VtbBffResponseParser.normalizeMetal("Платина")).isEqualTo("Платина");
    }

    @Test
    void coinMatchesQueryIsCaseInsensitive() {
        Coin coin = new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 99700.0);
        assertThat(VtbBffResponseParser.coinMatchesQuery(coin, "ПОБЕД")).isTrue();
        assertThat(VtbBffResponseParser.coinMatchesQuery(coin, "николай")).isFalse();
    }

    @Test
    void dedupeKeyPrefersId() throws Exception {
        JsonNode row = VtbBffResponseParser.parseListResponse(loadJson("list_page1.json")).coins().get(0);
        Coin coin = VtbBffResponseParser.rowToCoin(row).orElseThrow();
        assertThat(VtbBffResponseParser.dedupeKey(row, coin)).isEqualTo("vtb-1001");
    }

    @Test
    void captchaDetection() {
        assertThat(VtbBffResponseParser.isCaptchaTitle("Security Check - CAPTCHA")).isTrue();
        assertThat(VtbBffResponseParser.isCaptchaBody("Подтвердите, что вы не робот")).isTrue();
        assertThat(VtbBffResponseParser.isCaptchaTitle("Каталог монет")).isFalse();
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (var in = VtbBffResponseParserTest.class.getResourceAsStream("/fixtures/vtb/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }
}
