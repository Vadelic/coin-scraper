package ru.scraper.coincatalog.scraper.lanta;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class LantaPageParserTest {

    @Test
    void parseArticleFromPopupHtml() throws Exception {
        assertThat(LantaPageParser.parseArticleFromPopupHtml(loadFixture("popup_georgiy.html")))
                .isEqualTo("5216-0060");
        assertThat(LantaPageParser.parseArticleFromPopupHtml(loadFixture("popup_silver.html")))
                .isEqualTo("5111-0008");
    }

    @Test
    void metalAndWeightFromInfoLines() {
        List<String> info = List.of("Золото 999", "Масса монеты: 7,78 г", "Диаметр 22 мм");
        assertThat(LantaPageParser.metalFromInfoLines(info)).isEqualTo("Золото");
        assertThat(LantaPageParser.weightGFromInfoLines(info)).isEqualTo(7.78);
    }

    @Test
    void parseListPricesWithOutOfStock() {
        var prices = LantaPageParser.parseListPrices("нет в наличии", "10 000 ₽", true);
        assertThat(prices.sellPrice()).isNull();
        assertThat(prices.buyPrice()).isEqualTo(10000.0);
    }

    @Test
    void listItemToCoin() {
        var item = new LantaPageParser.ListItem(
                "12345",
                "892",
                "Золотая монета «Георгий Победоносец»",
                "99 700 ₽",
                "выкуп 85 000 ₽",
                false,
                List.of("Золото 999", "Масса монеты: 7,78 г"));

        var coin = LantaPageParser.listItemToCoin(item, "5216-0060").orElseThrow();

        assertThat(coin.catalogNumber()).isEqualTo("5216-0060");
        assertThat(coin.name()).contains("Победоносец");
        assertThat(coin.metal()).isEqualTo("Золото");
        assertThat(coin.weightG()).isEqualTo(7.78);
        assertThat(coin.sellPrice()).isEqualTo(99700.0);
        assertThat(coin.buyPrice()).isEqualTo(85000.0);
    }

    @Test
    void dedupeKeyUsesDataId() {
        var item = new LantaPageParser.ListItem("42", "892", "Coin", "", "", false, List.of());
        var coin = new ru.scraper.coincatalog.model.Coin(null, "Coin", null, null, null, null);
        assertThat(LantaPageParser.dedupeKey(item, coin)).isEqualTo("id:42");
    }

    @Test
    void detectCaptcha() {
        assertThat(LantaPageParser.isCaptchaTitle("Captcha Challenge")).isTrue();
        assertThat(LantaPageParser.isCaptchaBody("Подтвердите, что вы не робот")).isTrue();
        assertThat(LantaPageParser.isCaptchaBody("Каталог монет")).isFalse();
    }

    @Test
    void resolveCatalogUrl() {
        assertThat(LantaPageParser.resolveCatalogUrl(false)).contains("/metals/coins/");
        assertThat(LantaPageParser.resolveCatalogUrl(true)).contains("ivesticyonnie-monety");
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = LantaPageParserTest.class.getResourceAsStream("/fixtures/lanta/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
