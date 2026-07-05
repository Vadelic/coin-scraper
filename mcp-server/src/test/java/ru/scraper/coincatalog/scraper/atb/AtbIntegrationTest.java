package ru.scraper.coincatalog.scraper.atb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class AtbIntegrationTest {

    private static final List<ExpectedCoin> PO_EXPECTED = List.of(
            new ExpectedCoin(
                    "5216-0060",
                    "Георгий Победоносец (50 рублей)",
                    "Золото",
                    7.89,
                    81_000.0,
                    95_000.0),
            new ExpectedCoin(
                    "5111-0178",
                    "Георгий Победоносец (серебро)",
                    "Серебро",
                    31.5,
                    null,
                    7_700.0),
            new ExpectedCoin(
                    "5215-0036",
                    "Георгий Победоносец (25 рублей)",
                    "Золото",
                    3.2,
                    null,
                    42_000.0),
            new ExpectedCoin(
                    "5219-0033",
                    "Георгий Победоносец (200 рублей)",
                    "Золото",
                    31.37,
                    324_000.0,
                    null),
            new ExpectedCoin(
                    "5217-0048",
                    "Георгий Победоносец (100 рублей)",
                    "Золото",
                    15.72,
                    162_000.0,
                    null));

    private static final List<ExpectedCoin> PO_CATALOG_EXTRA = List.of(
            new ExpectedCoin(
                    "5115-0015",
                    "Полярный-97 (25 рублей)",
                    "Серебро",
                    173.29,
                    null,
                    52_000.0),
            new ExpectedCoin(
                    null,
                    "Почувствуй любовь-20",
                    "Серебро",
                    17.5,
                    null,
                    7_600.0),
            new ExpectedCoin(
                    null,
                    "Подсолнечник (Helianthus)-13",
                    "Серебро",
                    14.14,
                    null,
                    4_000.0),
            new ExpectedCoin(
                    null,
                    "Пожелание благополучия – 17",
                    "Серебро",
                    null,
                    null,
                    5_000.0),
            new ExpectedCoin(
                    null,
                    "Подкова-14",
                    "Серебро",
                    14.44,
                    null,
                    4_600.0),
            new ExpectedCoin(
                    "5015-0071",
                    "25 лет со дня подписания Договора о создании Союзного государства",
                    null,
                    10.0,
                    null,
                    5_000.0));

    // https://www.atb.su/vklady-i-scheta/monety/ — AJAX category=479, name=по
    @Test
    void poInvestmentCatalog() {
        ScrapePayload<Coin> payload =
                new AtbScraper().scrape("по", true, null);
        assertCatalog(payload, PO_EXPECTED, 4, 1, 1, PO_EXPECTED.size());
    }

    // https://www.atb.su/vklady-i-scheta/monety/ — AJAX name=по (весь каталог)
    @Test
    void poCatalog() {
        ScrapePayload<Coin> payload =
                new AtbScraper().scrape("по", false, null);
        List<ExpectedCoin> expected =
                Stream.concat(PO_EXPECTED.stream(), PO_CATALOG_EXTRA.stream()).toList();
        assertCatalog(payload, expected, 4, 6, 1, expected.size());
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
            assertThat(coin.buyPrice()).isEqualTo(exp.buyPrice());
            assertThat(coin.sellPrice()).isEqualTo(exp.sellPrice());
            if (coin.buyPrice() != null && coin.sellPrice() != null) {
                assertThat(coin.buyPrice()).isLessThan(coin.sellPrice());
            }
        }
    }

    private static Coin findCoin(List<Coin> coins, ExpectedCoin expected) {
        return coins.stream()
                .filter(c -> java.util.Objects.equals(expected.catalogNumber(), c.catalogNumber()))
                .filter(c -> expected.name().equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Coin not found: " + expected));
    }

    private record ExpectedCoin(
            String catalogNumber,
            String name,
            String metal,
            Double weightG,
            Double buyPrice,
            Double sellPrice) {}
}
