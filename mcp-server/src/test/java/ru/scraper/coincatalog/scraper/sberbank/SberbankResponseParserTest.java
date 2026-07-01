package ru.scraper.coincatalog.scraper.sberbank;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SberbankResponseParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildPayloadIncludesInvestmentSections() {
        ObjectNode payload = SberbankResponseParser.buildPayload(
                0,
                4000,
                "Москва",
                1,
                "победоносец",
                List.of("Золото"),
                List.of(SberbankResponseParser.INVESTMENT_SECTION),
                List.of());

        assertThat(payload.get("query").asText()).isEqualTo("победоносец");
        assertThat(payload.get("metals")).hasSize(1);
        assertThat(payload.get("sections").get(0).asText()).isEqualTo("Инвестиционные монеты");
        assertThat(payload.get("pageSize").asInt()).isEqualTo(4000);
    }

    @Test
    void resolveSectionsForInvestmentOnly() {
        assertThat(SberbankResponseParser.resolveSections(true))
                .containsExactly(SberbankResponseParser.INVESTMENT_SECTION);
        assertThat(SberbankResponseParser.resolveSections(false)).isEmpty();
    }

    @Test
    void buildBuyoutPathEncodesQuery() {
        String path = SberbankResponseParser.buildBuyoutPath(0, 4000, "золото");
        assertThat(path).startsWith(SberbankResponseParser.API_BUYOUT_PATH + "?");
        assertThat(path).contains("pageSize=4000");
    }

    @Test
    void parsesCatalogEntitiesFromFixture() throws Exception {
        JsonNode data = loadJson("catalog_gold.json");
        List<ObjectNode> entities = SberbankResponseParser.entitiesFromCatalogResponse(data);

        assertThat(entities).hasSize(1);
        assertThat(entities.get(0).get("catalogNumber").asText()).isEqualTo("5216-0060");
    }

    @Test
    void mergeEntitiesWithMetalFiltersDeduplicatesById() throws Exception {
        List<ObjectNode> gold = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_gold.json"));
        List<ObjectNode> silver = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_silver.json"));

        var merged = SberbankResponseParser.mergeEntitiesWithMetalFilters(List.of(
                new SberbankResponseParser.MetalBatch("Золото", gold),
                new SberbankResponseParser.MetalBatch("Серебро", silver)));

        assertThat(merged.entities()).hasSize(2);
        assertThat(merged.entities().get(0).get("metal").asText()).isEqualTo("Золото");
        assertThat(merged.entities().get(1).get("metal").asText()).isEqualTo("Серебро");
        assertThat(merged.rawRowCount()).isEqualTo(2);
    }

    @Test
    void mergeEntitiesKeepsFirstMetalOnDuplicateId() throws Exception {
        ObjectNode duplicate = (ObjectNode) MAPPER.readTree("""
                {"entities":[{"id":"dup","name":"Same coin","price":100}]}
                """);
        List<ObjectNode> first = SberbankResponseParser.entitiesFromCatalogResponse(duplicate);
        List<ObjectNode> second = SberbankResponseParser.entitiesFromCatalogResponse(duplicate);

        var merged = SberbankResponseParser.mergeEntitiesWithMetalFilters(List.of(
                new SberbankResponseParser.MetalBatch("Золото", first),
                new SberbankResponseParser.MetalBatch("Серебро", second)));

        assertThat(merged.entities()).hasSize(1);
        assertThat(merged.entities().get(0).get("metal").asText()).isEqualTo("Золото");
        assertThat(merged.rawRowCount()).isEqualTo(2);
    }

    @Test
    void mergesBuyoutPricesIntoCatalog() throws Exception {
        List<ObjectNode> catalog = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_gold.json"));
        List<JsonNode> buyout = SberbankResponseParser.entitiesFromBuyoutResponse(loadJson("buyout.json"));

        SberbankResponseParser.mergeBuyoutIntoCatalog(catalog, buyout);

        assertThat(catalog.get(0).get("priceBuy").asDouble()).isEqualTo(89500.0);
    }

    @Test
    void entitiesFromBuyoutResponseSupportsObjectWrapper() throws Exception {
        JsonNode wrapped = MAPPER.readTree("{\"entities\":[{\"id\":\"1\",\"priceBuy\":10}]}");
        assertThat(SberbankResponseParser.entitiesFromBuyoutResponse(wrapped)).hasSize(1);
    }

    @Test
    void entityToCoinMapsFields() throws Exception {
        List<ObjectNode> entities = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_gold.json"));
        entities.get(0).put("metal", "Золото");
        entities.get(0).put("priceBuy", 89500);

        var coin = SberbankResponseParser.entityToCoin(entities.get(0)).orElseThrow();

        assertThat(coin.catalogNumber()).isEqualTo("5216-0060");
        assertThat(coin.name()).contains("Победоносец");
        assertThat(coin.metal()).isEqualTo("Золото");
        assertThat(coin.weightG()).isEqualTo(7.78);
        assertThat(coin.buyPrice()).isEqualTo(89500.0);
        assertThat(coin.sellPrice()).isEqualTo(99700.0);
    }

    @Test
    void coinMatchesQueryIsCaseInsensitiveSubstring() {
        var coin = new ru.scraper.coincatalog.model.Coin(
                "5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 99700.0);

        assertThat(SberbankResponseParser.coinMatchesQuery(coin, "ПОБЕД")).isTrue();
        assertThat(SberbankResponseParser.coinMatchesQuery(coin, "платина")).isFalse();
        assertThat(SberbankResponseParser.coinMatchesQuery(coin, "")).isTrue();
    }

    @Test
    void catalogResponseRequiresObject() {
        assertThatThrownBy(() -> SberbankResponseParser.entitiesFromCatalogResponse(MAPPER.createArrayNode()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("объект JSON");
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (var in = SberbankResponseParserTest.class.getResourceAsStream("/fixtures/sberbank/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }
}
