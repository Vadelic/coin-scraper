package ru.scraper.coincatalog.scraper.atb;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class AtbPageParserTest {

    @Test
    void buildRequestBodyWithCategoryAndQuery() {
        String body = AtbPageParser.buildRequestBody("479", "победоносец");
        assertThat(body).contains("category=479");
        assertThat(body).contains("name=");
        assertThat(body).contains("ajax=true");
    }

    @Test
    void resolveCategoryForInvestmentOnly() {
        assertThat(AtbPageParser.resolveCategory(true)).isEqualTo("479");
        assertThat(AtbPageParser.resolveCategory(false)).isEmpty();
    }

    @Test
    void parseCardsFromFragment() throws Exception {
        List<AtbPageParser.CardMatch> cards =
                AtbPageParser.parseCardsFromFragment(loadFixture("ajax_fragment.html"));
        assertThat(cards).hasSize(2);
        assertThat(cards.get(0).href()).contains("georgiy-pobedonosets");
    }

    @Test
    void parseDetailFieldsFromFixture() throws Exception {
        AtbPageParser.DetailFields fields =
                AtbPageParser.parseDetailFields(loadFixture("detail_georgiy.html"));
        assertThat(fields.catalogNumber()).isEqualTo("5216-0060");
        assertThat(fields.metal()).isEqualTo("Золото");
        assertThat(fields.weightG()).isEqualTo(7.78);
    }

    @Test
    void parseCardWithDetail() throws Exception {
        List<AtbPageParser.CardMatch> cards =
                AtbPageParser.parseCardsFromFragment(loadFixture("ajax_fragment.html"));
        var parsed = AtbPageParser.parseCard(
                cards.get(0).cardHtml(),
                cards.get(0).href(),
                loadFixture("detail_georgiy.html"));

        assertThat(parsed).isPresent();
        assertThat(parsed.get().coin().name()).contains("Победоносец");
        assertThat(parsed.get().coin().sellPrice()).isEqualTo(99700.0);
        assertThat(parsed.get().coin().buyPrice()).isNull();
    }

    @Test
    void parseSecondCardSilver() throws Exception {
        List<AtbPageParser.CardMatch> cards =
                AtbPageParser.parseCardsFromFragment(loadFixture("ajax_fragment.html"));
        var parsed = AtbPageParser.parseCard(
                cards.get(1).cardHtml(),
                cards.get(1).href(),
                loadFixture("detail_silver.html"));

        assertThat(parsed.get().coin().catalogNumber()).isEqualTo("5111-0008");
        assertThat(parsed.get().coin().metal()).isEqualTo("Серебро");
        assertThat(parsed.get().coin().weightG()).isEqualTo(31.1);
    }

    @Test
    void detectCaptchaAndEmptySearch() throws Exception {
        assertThat(AtbPageParser.detectCaptcha("<html>подтвердите что вы не робот</html>")).isTrue();
        assertThat(AtbPageParser.isEmptySearchResult(loadFixture("ajax_empty.html"))).isTrue();
    }

    @Test
    void normalizeMetalVariants() {
        assertThat(AtbPageParser.normalizeMetal("медно-никелевый сплав")).isEqualTo("Медно-никелевый сплав");
        assertThat(AtbPageParser.normalizeMetal("платина")).isEqualTo("Платина");
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = AtbPageParserTest.class.getResourceAsStream("/fixtures/atb/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
