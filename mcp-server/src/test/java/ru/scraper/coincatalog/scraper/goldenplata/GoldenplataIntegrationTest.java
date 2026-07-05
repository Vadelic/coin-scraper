package ru.scraper.coincatalog.scraper.goldenplata;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class GoldenplataIntegrationTest {

    private static final List<ExpectedCoin> PO_INVESTMENT_EXPECTED = List.of(
            new ExpectedCoin(
                    "7872",
                    "Монета \"Георгий Победоносец\", СПМД, 2025 г., Золото, 31,1 гр., проба 999, РОССИЯ",
                    "Золото",
                    31.1,
                    391_000.0),
            new ExpectedCoin(
                    "7875",
                    "Монета \"Георгий Победоносец\", ММД, 2025 г., Золото, 15,55 гр., проба 999, РОССИЯ",
                    "Золото",
                    15.55,
                    195_500.0),
            new ExpectedCoin(
                    "7361",
                    "Монета \"Георгий Победоносец\", СПМД, 2025 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    92_800.0),
            new ExpectedCoin(
                    "7935",
                    "Монета \"Георгий Победоносец\", СПМД, 2006 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    91_800.0),
            new ExpectedCoin(
                    "7733",
                    "Монета \"Георгий Победоносец\", ММД, 2025 г., Серебро, 31,1 гр., проба 999, РОССИЯ",
                    "Серебро",
                    31.1,
                    7_400.0),
            new ExpectedCoin(
                    "8234",
                    "Монета \"100-летие золотого червонца\", 2023 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    705_000.0),
            new ExpectedCoin(
                    "7926",
                    "Монета \"Золотой червонец Сеятель\", 1980 г., Золото, 7,742 гр., проба 900, РОССИЯ",
                    "Золото",
                    7.742,
                    104_300.0),
            new ExpectedCoin(
                    "7939",
                    "Монета \"Георгий Победоносец\", ММД, 2006 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    null));

    private static final List<ExpectedCoin> PO_CATALOG_EXPECTED = List.of(
            new ExpectedCoin(
                    "8278",
                    "Набор из 3-х монет \"Кенгуру - идеальное вложение\", 2025 г., Платина, Золото, Серебро, по 0",
                    "Золото",
                    null,
                    36_300.0),
            new ExpectedCoin(
                    "8249",
                    "Монета \"75-летие Победы советского народа в Великой Отечественной войне 1941–1945 гг.\", СП",
                    "Серебро",
                    null,
                    25_400.0),
            new ExpectedCoin(
                    "8180",
                    "Монета \"Безграничный футбол. Чемпионат мира по футболу 2026\", 2026 г., 124,4 гр., проба 99",
                    "Серебро",
                    124.4,
                    89_000.0),
            new ExpectedCoin(
                    "8179",
                    "Монета \"Футболист. Чемпионат мира по футболу 2026\", 2026 г., 155,5 гр., проба 999, САМОА",
                    "Серебро",
                    155.5,
                    112_000.0),
            new ExpectedCoin(
                    "7970",
                    "Монета \"Георгий Победоносец\", ММД, 2010 г., Серебро, 31,1 гр., проба 999, РОССИЯ",
                    "Серебро",
                    31.1,
                    7_200.0),
            new ExpectedCoin(
                    "7948",
                    "Монета \"Георгий Победоносец\", ММД, 2010 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    92_100.0),
            new ExpectedCoin(
                    "7946",
                    "Монета \"Георгий Победоносец\", ММД, 2022 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    93_100.0),
            new ExpectedCoin(
                    "7945",
                    "Монета \"Георгий Победоносец\", ММД, 2012 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    92_100.0),
            new ExpectedCoin(
                    "7942",
                    "Монета \"Георгий Победоносец\", СПМД, 2020 г., Серебро, 31,1 гр., проба 999, РОССИЯ",
                    "Серебро",
                    31.1,
                    7_200.0),
            new ExpectedCoin(
                    "7938",
                    "Монета \"Георгий Победоносец\", СПМД, 2019 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    92_800.0),
            new ExpectedCoin(
                    "7935",
                    "Монета \"Георгий Победоносец\", СПМД, 2006 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    91_800.0),
            new ExpectedCoin(
                    "7934",
                    "Монета \"Георгий Победоносец\", СПМД, 2008 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    91_800.0),
            new ExpectedCoin(
                    "7933",
                    "Монета \"Георгий Победоносец\", СПМД, 2012 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    91_800.0),
            new ExpectedCoin(
                    "7931",
                    "Монета \"Георгий Победоносец\", ММД, 2009 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    92_100.0),
            new ExpectedCoin(
                    "7930",
                    "Монета \"Георгий Победоносец\", СПМД, 2021 г., Золото, 3,11 гр., проба 999, РОССИЯ",
                    "Золото",
                    3.11,
                    48_500.0),
            new ExpectedCoin(
                    "7928",
                    "Монета \"Георгий Победоносец\", СПМД, 2009 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    91_800.0),
            new ExpectedCoin(
                    "7927",
                    "Монета \"Георгий Победоносец\", ММД, 2008 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    92_100.0),
            new ExpectedCoin(
                    "7925",
                    "Монета \"Георгий Победоносец\", ММД, 2019 г., Золото, 7,78 гр., проба 999, РОССИЯ",
                    "Золото",
                    7.78,
                    93_100.0));

    // https://goldenplata.ru/catalog/investitsionnye-monety/rossiyskiye/?q=по
    @Test
    void poInvestmentCatalog() {
        ScrapePayload<Coin> payload =
                new GoldenplataScraper().scrape("по", true, null);
        assertCatalog(payload, PO_INVESTMENT_EXPECTED, 7, 1, 1, PO_INVESTMENT_EXPECTED.size());
    }

    // https://goldenplata.ru/catalog/?q=по
    @Test
    void poCatalog() {
        ScrapePayload<Coin> payload =
                new GoldenplataScraper().scrape("по", false, null);
        assertCatalog(payload, PO_CATALOG_EXPECTED, 13, 5, 1, PO_CATALOG_EXPECTED.size());
    }

    private static void assertCatalog(
            ScrapePayload<Coin> payload,
            List<ExpectedCoin> expected,
            int goldCount,
            int silverCount,
            int pagesProcessed,
            int totalCoins) {
        List<Coin> coins = payload.coins();

        assertThat(payload.pagesProcessed()).isEqualTo(pagesProcessed);
        assertThat(coins.stream().filter(c -> "Серебро".equals(c.metal()))).hasSize(silverCount);
        assertThat(coins.stream().filter(c -> "Золото".equals(c.metal()))).hasSize(goldCount);
        assertThat(coins).hasSize(totalCoins);

        for (ExpectedCoin exp : expected) {
            Coin coin = findCoin(coins, exp);
            assertThat(coin.name()).isEqualTo(exp.name());
            assertThat(coin.metal()).isEqualTo(exp.metal());
            if (exp.weightG() != null) {
                assertThat(coin.weightG()).isEqualTo(exp.weightG());
            } else {
                assertThat(coin.weightG()).isNull();
            }
            assertThat(coin.buyPrice()).isNull();
            assertThat(coin.sellPrice()).isEqualTo(exp.sellPrice());
        }
    }

    private static Coin findCoin(List<Coin> coins, ExpectedCoin expected) {
        return coins.stream()
                .filter(c -> expected.catalogNumber().equals(c.catalogNumber()))
                .filter(c -> expected.name().equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Coin not found: " + expected));
    }

    private record ExpectedCoin(
            String catalogNumber,
            String name,
            String metal,
            Double weightG,
            Double sellPrice) {}
}
