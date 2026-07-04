package ru.scraper.coincatalog.scraper.aurumex;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AurumexScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final AurumexScraper fetcher = new AurumexScraper();

    @Test
    void filtersByQuery() throws Exception {
        List<Coin> coins = fetcher.mergeAndFilter(List.of(loadJson("payload_page1.json")), "серебр");

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5111-0008");
    }

    @Test
    void mergesMultiplePagesWithoutDuplicates() throws Exception {
        List<Coin> coins = fetcher.mergeAndFilter(
                List.of(loadJson("payload_page1.json"), loadJson("payload_page2.json")), "");

        assertThat(coins).hasSize(3);
    }

    @Test
    void filtersByQueryOnMergedPages() throws Exception {
        List<Coin> coins = fetcher.mergeAndFilter(
                List.of(loadJson("payload_page1.json"), loadJson("payload_page2.json")), "победоносец");

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (var in = AurumexScraperTest.class.getResourceAsStream("/fixtures/aurumex/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }
}
