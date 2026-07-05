package ru.scraper.coincatalog.scraper.lanta;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

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
        var prices = LantaPageParser.parseListPrices("нет в наличии", "10 000 ₽ покупка", true);
        assertThat(prices.sellPrice()).isNull();
        assertThat(prices.buyPrice()).isEqualTo(10000.0);
    }

    @Test
    void parseListPricesUsesLastAmountWhenMultiplePrices() {
        var prices = LantaPageParser.parseListPrices("100 200 ₽ 93 500 ₽", "83 000 ₽ 81 500 ₽ покупка", false);
        assertThat(prices.sellPrice()).isEqualTo(93500.0);
        assertThat(prices.buyPrice()).isEqualTo(81500.0);
    }

    @Test
    void applyPopupTradeSides() {
        var onlySellToBank = LantaPageParser.applyPopupTradeSides(
                new LantaPageParser.ListPrices(37900.0, 99700.0),
                """
                <a class="tabs__item-link js-tab-link is-active" data-type="Продать"><span>Продать</span></a>
                """);
        assertThat(onlySellToBank.sellPrice()).isNull();
        assertThat(onlySellToBank.buyPrice()).isEqualTo(37900.0);

        var onlyBuyFromBank = LantaPageParser.applyPopupTradeSides(
                new LantaPageParser.ListPrices(5700.0, 6900.0),
                """
                <a class="tabs__item-link js-tab-link is-active" data-type="Купить"><span>Купить</span></a>
                """);
        assertThat(onlyBuyFromBank.sellPrice()).isEqualTo(6900.0);
        assertThat(onlyBuyFromBank.buyPrice()).isNull();

        var bothSides = LantaPageParser.applyPopupTradeSides(
                new LantaPageParser.ListPrices(81500.0, 93500.0),
                """
                <a data-type="Купить"></a><a data-type="Продать"></a>
                """);
        assertThat(bothSides.sellPrice()).isEqualTo(93500.0);
        assertThat(bothSides.buyPrice()).isEqualTo(81500.0);
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
    void collapseCatalogVariantsKeepsAllInStockMintsSeparate() {
        var spmd = candidate(
                "27123",
                "Георгий Победоносец (СПМД)",
                "100 200 ₽",
                "83 000 ₽ покупка",
                false,
                "5216-0060",
                100200.0,
                83000.0);
        var mmd = candidate(
                "27401",
                "Георгий Победоносец (ММД)",
                "100 500 ₽",
                "83 000 ₽ покупка",
                false,
                "5216-0060",
                100500.0,
                83000.0);

        var collapsed = LantaPageParser.collapseCatalogVariants(List.of(spmd, mmd));

        assertThat(collapsed).hasSize(2);
        assertThat(collapsed.stream().map(c -> c.coin().name()).toList())
                .containsExactly("Георгий Победоносец (СПМД)", "Георгий Победоносец (ММД)");
        assertThat(collapsed.stream().map(c -> c.coin().sellPrice()).toList())
                .containsExactly(100200.0, 100500.0);
    }

    @Test
    void collapseCatalogVariantsMergesWhenOutOfStockDuplicate() {
        var spmdOut = candidate(
                "69389",
                "Георгий Победоносец (СПМД)",
                "Нет в продаже",
                "166 000 ₽ покупка",
                true,
                "5217-0048",
                null,
                166000.0);
        var mmdIn = candidate(
                "71720",
                "Георгий Победоносец (ММД)",
                "203 500 ₽",
                "166 000 ₽ покупка",
                false,
                "5217-0048",
                203500.0,
                166000.0);

        var collapsed = LantaPageParser.collapseCatalogVariants(List.of(spmdOut, mmdIn));

        assertThat(collapsed).hasSize(1);
        Coin coin = collapsed.getFirst().coin();
        assertThat(coin.catalogNumber()).isEqualTo("5217-0048");
        assertThat(coin.name()).isEqualTo("Георгий Победоносец");
        assertThat(coin.sellPrice()).isEqualTo(203500.0);
        assertThat(coin.buyPrice()).isEqualTo(166000.0);
    }

    @Test
    void dedupeKeyUsesDataId() {
        var item = new LantaPageParser.ListItem("42", "892", "Coin", "", "", false, List.of());
        var coin = new Coin(null, "Coin", null, null, null, null);
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
        assertThat(LantaPageParser.resolveCatalogUrl(false)).isEqualTo(LantaPageParser.CATALOG_URL);
        assertThat(LantaPageParser.resolveCatalogUrl(true)).isEqualTo(LantaPageParser.INVESTMENT_CATALOG_URL);
        assertThat(LantaPageParser.CATALOG_URL).doesNotContain("/petersburg/");
    }

    private static LantaPageParser.CoinCandidate candidate(
            String id,
            String name,
            String sellRaw,
            String buyRaw,
            boolean outOfStock,
            String catalogNumber,
            Double sellPrice,
            Double buyPrice) {
        var item = new LantaPageParser.ListItem(
                id, "892", name, sellRaw, buyRaw, outOfStock, List.of("Золото 999", "Масса монеты: 7,78 г"));
        var coin = new Coin(catalogNumber, name, "Золото", 7.78, buyPrice, sellPrice);
        return new LantaPageParser.CoinCandidate(item, coin);
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = LantaPageParserTest.class.getResourceAsStream("/fixtures/lanta/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
