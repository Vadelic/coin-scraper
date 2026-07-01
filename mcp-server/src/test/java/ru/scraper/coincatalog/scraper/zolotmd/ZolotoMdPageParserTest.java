package ru.scraper.coincatalog.scraper.zolotmd;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ZolotoMdPageParserTest {

    @Test
    void parsesCoinsFromFixture() throws Exception {
        String html = loadFixture("catalog_page.html");
        var parsed = ZolotoMdPageParser.parseCoins(html);

        assertThat(parsed).hasSize(2);
        assertThat(parsed.get(0).coin().name()).contains("Победоносец");
        assertThat(parsed.get(0).coin().catalogNumber()).isEqualTo("5216-0060");
        assertThat(parsed.get(0).coin().metal()).isEqualTo("Золото");
        assertThat(parsed.get(0).coin().weightG()).isEqualTo(7.78);
        assertThat(parsed.get(0).coin().sellPrice()).isEqualTo(99700.0);
        assertThat(parsed.get(0).coin().buyPrice()).isEqualTo(89500.0);
        assertThat(parsed.get(1).coin().metal()).isEqualTo("Серебро");
        assertThat(parsed.get(1).coin().buyPrice()).isNull();
    }

    @Test
    void parseTotalPagesFromFixture() throws Exception {
        String html = loadFixture("catalog_page.html");
        assertThat(ZolotoMdPageParser.parseTotalPages(html, 1)).isEqualTo(2);
    }

    @Test
    void buildCatalogUrlWithInvestmentAndQuery() {
        String url = ZolotoMdPageParser.buildCatalogUrl(2, 100, "победоносец", true);
        assertThat(url).contains("page=2");
        assertThat(url).contains("country=");
        assertThat(url).contains("query=");
    }

    @Test
    void normalizeMetalFromName() {
        assertThat(ZolotoMdPageParser.normalizeMetal("7,78 г чистого золота"))
                .isEqualTo("Золото");
    }

    @Test
    void parseWeightFromName() {
        assertThat(ZolotoMdPageParser.parseWeightG("монета 7,78 г чистого золота"))
                .isEqualTo(7.78);
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = ZolotoMdPageParserTest.class.getResourceAsStream("/fixtures/zoloto-md/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
