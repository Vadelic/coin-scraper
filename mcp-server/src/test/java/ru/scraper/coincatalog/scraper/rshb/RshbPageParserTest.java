package ru.scraper.coincatalog.scraper.rshb;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class RshbPageParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void buildUrlWithQueryAndInvestment() {
        String url = RshbPageParser.buildUrl(2, 99, "победоносец", true);
        assertThat(url).contains("page=2");
        assertThat(url).contains("page_size=99");
        assertThat(url).contains("in_stock=true");
        assertThat(url).contains("search_text=");
        assertThat(url).contains("subjects=" + RshbPageParser.INVESTMENT_SUBJECTS);
    }

    @Test
    void parseSkuFromProductHref() {
        assertThat(RshbPageParser.parseSkuFromProductHref("/p/5216-0060/georgiy"))
                .isEqualTo("5216-0060");
        assertThat(RshbPageParser.parseSkuFromProductHref("/catalog")).isNull();
    }

    @Test
    void parsePaginationMax() {
        assertThat(RshbPageParser.parsePaginationMax(List.of("?page=1", "?page=5", "?page=3")))
                .isEqualTo(5);
    }

    @Test
    void parseGeorgiyCard() throws Exception {
        var parsed = RshbPageParser.parseCard(new RshbPageParser.CardInput(
                        loadFixture("card_georgiy.txt"),
                        "/p/5216-0060/georgiy",
                        "Золотая монета «Георгий Победоносец»",
                        "107 000"))
                .orElseThrow();

        assertThat(parsed.coin().catalogNumber()).isEqualTo("5216-0060");
        assertThat(parsed.coin().name()).contains("Победоносец");
        assertThat(parsed.coin().metal()).isEqualTo("Золото");
        assertThat(parsed.coin().weightG()).isEqualTo(7.78);
        assertThat(parsed.coin().sellPrice()).isEqualTo(107000.0);
        assertThat(parsed.coin().buyPrice()).isEqualTo(85000.0);
        assertThat(parsed.url()).contains("/p/5216-0060");
    }

    @Test
    void parseCardSkipsBlankLinesBetweenLabelAndValue() {
        // Live SSR HTML yields blank lines between attribute labels and values.
        String rawText = """
                98 000



                Георгий Победоносец 50 руб. (7,78гр.)


                Номинал


                50 RUB



                Металл


                Золото



                Проба


                999



                Чистого металла


                7.78 г
                """;
        var parsed = RshbPageParser.parseCard(new RshbPageParser.CardInput(
                        rawText,
                        "/p/5216-0060%D1%81/pobedonosec",
                        rawText,
                        "98 000"))
                .orElseThrow();

        assertThat(parsed.coin().metal()).isEqualTo("Золото");
        assertThat(parsed.coin().weightG()).isEqualTo(7.78);
        assertThat(parsed.coin().sellPrice()).isEqualTo(98000.0);
    }

    @Test
    void parseOutOfStockCardHasNoSellPrice() throws Exception {
        var parsed = RshbPageParser.parseCard(new RshbPageParser.CardInput(
                        loadFixture("card_silver_out.txt"),
                        "/p/5111-0008/silver",
                        "Серебряная монета 31,1 г",
                        ""))
                .orElseThrow();

        assertThat(parsed.coin().catalogNumber()).isEqualTo("5111-0008");
        assertThat(parsed.coin().metal()).isEqualTo("Серебро");
        assertThat(parsed.coin().sellPrice()).isNull();
    }

    @Test
    void buyoutPriceFromProductSource() throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, Object> source = MAPPER.readValue(loadFixture("buyout_source.json"), Map.class);
        assertThat(RshbPageParser.buyoutPriceFromProductSource(source)).isEqualTo(85000.0);
        assertThat(RshbPageParser.buyoutPriceFromProductSource(Map.of("can_be_buyouted", 0))).isNull();
    }

    @Test
    void extractNameFromListing() {
        String text = "Золотая монета «Георгий Победоносец»\nМеталл\nЗолото\n107 000 ₽";
        assertThat(RshbPageParser.extractNameFromListing(text)).contains("Победоносец");
    }

    @Test
    void parseCardsFromHtml() throws Exception {
        String html = loadFixture("catalog_page.html");
        List<RshbPageParser.ParsedCard> cards = RshbPageParser.parseCardsFromHtml(html);

        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).coin().catalogNumber()).isEqualTo("5216-0060с");
        assertThat(cards.get(0).coin().metal()).isEqualTo("Золото");
        assertThat(cards.get(0).coin().sellPrice()).isEqualTo(98000.0);
        assertThat(cards.get(0).coin().buyPrice()).isEqualTo(85000.0);
        assertThat(cards.get(1).coin().catalogNumber()).isEqualTo("5111-0008");
        assertThat(RshbPageParser.parsePaginationMax(RshbPageParser.parsePaginationHrefs(html)))
                .isEqualTo(3);
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = RshbPageParserTest.class.getResourceAsStream("/fixtures/rshb/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
