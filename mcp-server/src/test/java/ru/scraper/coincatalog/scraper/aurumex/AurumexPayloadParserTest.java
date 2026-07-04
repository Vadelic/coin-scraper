package ru.scraper.coincatalog.scraper.aurumex;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AurumexPayloadParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void payloadUrlForPage() {
        assertThat(AurumexPayloadParser.payloadUrlForPage(1))
                .isEqualTo("https://aurumex.ru/catalog/_payload.json?availability=true");
        assertThat(AurumexPayloadParser.payloadUrlForPage(3))
                .contains("/catalog/page/3/_payload.json");
    }

    @Test
    void derefCellResolvesPointer() throws Exception {
        JsonNode store = loadJson("payload_page1.json");
        assertThat(AurumexPayloadParser.derefCell(store, store.get(1)).get("coins").asInt())
                .isEqualTo(2);
        assertThat(AurumexPayloadParser.derefCell(store, store.get(1).get("coins")).isArray())
                .isTrue();
    }

    @Test
    void extractCoinsFromFixture() throws Exception {
        List<Coin> coins = AurumexPayloadParser.extractCoinsFromStore(loadJson("payload_page1.json"));

        assertThat(coins).hasSize(2);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
        assertThat(coins.get(0).name()).contains("Победоносец");
        assertThat(coins.get(0).metal()).isEqualTo("Золото");
        assertThat(coins.get(0).weightG()).isEqualTo(7.78);
        assertThat(coins.get(0).sellPrice()).isEqualTo(99700.0);
        assertThat(coins.get(0).buyPrice()).isEqualTo(89500.0);
        assertThat(coins.get(1).metal()).isEqualTo("Серебро");
        assertThat(coins.get(1).buyPrice()).isNull();
    }

    @Test
    void skipsUnavailableCoins() throws Exception {
        List<Coin> coins = AurumexPayloadParser.extractCoinsFromStore(loadJson("payload_page1.json"));
        assertThat(coins).noneMatch(c -> "unavailable".equals(c.catalogNumber()));
    }

    @Test
    void missingCatalogBlockThrows() throws Exception {
        JsonNode empty = MAPPER.readTree("[]");
        assertThatThrownBy(() -> AurumexPayloadParser.extractCoinsFromStore(empty))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("блок каталога");
    }

    @Test
    void categoryToMetalMapsSlugs() {
        assertThat(AurumexPayloadParser.categoryToMetal(MAPPER.valueToTree("gold"))).isEqualTo("Золото");
        assertThat(AurumexPayloadParser.categoryToMetal(MAPPER.valueToTree("silver"))).isEqualTo("Серебро");
    }

    @Test
    void parsePackCountFromTitle() {
        assertThat(AurumexPayloadParser.parsePackCount("Набор 10 шт золото")).isEqualTo(10);
        assertThat(AurumexPayloadParser.parsePackCount("одна монета")).isNull();
    }

    @Test
    void coinMatchesQueryByNameOrArticle() {
        Coin coin = new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 99700.0);
        assertThat(AurumexPayloadParser.coinMatchesQuery(coin, "ПОБЕД")).isTrue();
        assertThat(AurumexPayloadParser.coinMatchesQuery(coin, "5216")).isTrue();
        assertThat(AurumexPayloadParser.coinMatchesQuery(coin, "платина")).isFalse();
    }

    @Test
    void captchaDetection() {
        assertThat(AurumexPayloadParser.isCaptchaTitle("Captcha")).isTrue();
        assertThat(AurumexPayloadParser.isCaptchaBody("выровнять картинку")).isTrue();
    }

    private static JsonNode loadJson(String name) throws Exception {
        try (var in = AurumexPayloadParserTest.class.getResourceAsStream("/fixtures/aurumex/" + name)) {
            String text = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            return MAPPER.readTree(text);
        }
    }
}
