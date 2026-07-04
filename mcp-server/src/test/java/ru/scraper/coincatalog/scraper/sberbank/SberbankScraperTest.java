package ru.scraper.coincatalog.scraper.sberbank;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class SberbankScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final SberbankScraper client = new SberbankScraper();

    @Test
    void convertsMergedEntitiesToCoins() throws Exception {
        List<Coin> coins = client.toCoins(loadMergedEntities(), "победоносец");

        assertThat(coins).hasSize(2);
        assertThat(coins.stream().map(c -> c.catalogNumber()).toList())
                .containsExactlyInAnyOrder("5216-0060", "5111-0178");
        assertThat(coins.stream().filter(c -> "5216-0060".equals(c.catalogNumber())).findFirst())
                .get()
                .extracting(c -> c.buyPrice())
                .isEqualTo(89500.0);
    }

    @Test
    void filtersByQueryLocally() throws Exception {
        List<Coin> coins = client.toCoins(loadMergedEntities(), "николай");

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5111-0008");
    }

    @Test
    void georgiyQueryIncludesSilverPobedonosets() throws Exception {
        List<Coin> coins = client.toCoins(loadMergedEntities(), "георгий победоносец");

        assertThat(coins).hasSize(2);
        assertThat(coins.stream().map(c -> c.catalogNumber()).toList())
                .containsExactlyInAnyOrder("5216-0060", "5111-0178");
    }

    @Test
    void deduplicatesByEntityId() throws Exception {
        ObjectNode duplicate = (ObjectNode) MAPPER.readTree("""
                {"id":"dup","name":"Same coin","catalogNumber":"X-1","price":100,"metal":"Золото"}
                """);
        List<Coin> coins = client.toCoins(List.of(duplicate, duplicate.deepCopy()), "");

        assertThat(coins).hasSize(1);
    }

    private static List<ObjectNode> loadMergedEntities() throws Exception {
        List<ObjectNode> gold = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_gold.json"));
        List<ObjectNode> silver = SberbankResponseParser.entitiesFromCatalogResponse(loadJson("catalog_silver.json"));
        gold.get(0).put("metal", "Золото");
        silver.get(0).put("metal", "Серебро");
        silver.get(1).put("metal", "Серебро");
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
