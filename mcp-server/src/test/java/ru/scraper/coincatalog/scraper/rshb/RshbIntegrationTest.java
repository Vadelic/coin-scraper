package ru.scraper.coincatalog.scraper.rshb;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.common.ScrapePayload;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class RshbIntegrationTest {

    private static final List<ExpectedCoin> PO_EXPECTED = List.of(
            new ExpectedCoin(
                    "5216-0060с",
                    "Георгий Победоносец 50 руб. (7,78гр.)",
                    "Золото",
                    7.78,
                    77_000.0,
                    100_000.0),
            new ExpectedCoin(
                    "5111-0178",
                    "Георгий Победоносец 3 руб., серебро",
                    "Серебро",
                    31.1,
                    4_900.0,
                    9_650.0),
            new ExpectedCoin(
                    "5219-0033с",
                    "Георгий Победоносец 200 руб. (31,10гр.)",
                    "Золото",
                    31.1,
                    310_000.0,
                    400_000.0),
            new ExpectedCoin(
                    "5216-0060м",
                    "Георгий Победоносец 50 руб. (7,78гр.) М",
                    "Золото",
                    7.78,
                    77_000.0,
                    100_000.0),
            new ExpectedCoin(
                    "5217-0048м",
                    "Георгий Победоносец 100 руб. (15.55 гр.) М",
                    "Золото",
                    15.55,
                    155_000.0,
                    196_000.0),
            new ExpectedCoin(
                    "5217-0048с",
                    "Георгий Победоносец 100 руб. (15,55 гр.)",
                    "Золото",
                    15.55,
                    155_000.0,
                    196_000.0));

    private static final List<ExpectedCoin> POBEDA_CATALOG_EXPECTED = List.of(
            new ExpectedCoin(
                    "5216-0128",
                    "Победа-25 (Юбилей Победы советского народа в Великой Отечественной войне), Золото 50 рублей",
                    "Золото",
                    7.78,
                    null,
                    280_000.0),
            new ExpectedCoin(
                    "5111-0516",
                    "Победа-25 (Юбилей Победы советского народа в Великой Отечественной войне), Серебро 3 рубля",
                    "Серебро",
                    31.1,
                    null,
                    35_000.0));

    // https://coins.rshb.ru/?in_stock=true&search_text=по&subjects=5506&page=1&page_size=99
    @Test
    void poInvestmentCatalog() {
        ScrapePayload<Coin> payload =
                new RshbScraper().scrape("по", true, null);
        assertCatalog(payload, PO_EXPECTED, 5, 1, 1, PO_EXPECTED.size());
    }

    // https://coins.rshb.ru/?search_text=победа&in_stock=true
    @Test
    void poCatalog() {
        ScrapePayload<Coin> payload =
                new RshbScraper().scrape("победа", false, null);
        assertCatalog(payload, POBEDA_CATALOG_EXPECTED, 1, 1, 1, POBEDA_CATALOG_EXPECTED.size());
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
            Double buyPrice,
            Double sellPrice) {}
}
