package ru.scraper.coincatalog.scraper.vtb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class VtbScraperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final VtbScraper fetcher = new VtbScraper();

    @Test
    void convertsRowsToCoins() throws Exception {
        List<Coin> coins = fetcher.toCoins(loadAllRows(), "победоносец");

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
    }

    @Test
    void filtersByQueryLocally() throws Exception {
        List<Coin> coins = fetcher.toCoins(loadAllRows(), "николай");

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5111-0008");
    }

    @Test
    void deduplicatesById() throws Exception {
        List<JsonNode> rows = loadAllRows();
        List<JsonNode> duplicated = new ArrayList<>(rows);
        duplicated.add(rows.get(0));

        List<Coin> coins = fetcher.toCoins(duplicated, "");

        assertThat(coins).hasSize(2);
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
