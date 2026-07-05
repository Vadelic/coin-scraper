package ru.scraper.coincatalog.scraper.vtb;

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
class VtbIntegrationTest {

    private static final List<ExpectedCoin> PO_EXPECTED = List.of(
            new ExpectedCoin("5111-0178", "Георгий Победоносец (серебро)", "Серебро", 31.1, null, 15_000.0),
            new ExpectedCoin("5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 132_900.0),
            new ExpectedCoin("5217-0048", "Георгий Победоносец", "Золото", 15.55, null, 253_200.0),
            new ExpectedCoin("5219-0033", "Георгий Победоносец", "Золото", 31.1, null, 493_000.0));

    private static final List<ExpectedCoin> PO_CATALOG_EXTRA = List.of(
            new ExpectedCoin("5216-0128", "ПОБЕДА", "Золото", 7.78, null, 250_000.0),
            new ExpectedCoin("40009", "Мадонна с Младенцем под яблоней (Л.Кранах ст.)", "Серебро", 50.0, null, 15_000.0),
            new ExpectedCoin("30049", "Сантьяго де Компостело", "Серебро", 50.0, null, 15_000.0),
            new ExpectedCoin("30019", "Талисман удачи - Подкова", "Биметалл (серебро с золотом)", 31.1, null, 10_098.0),
            new ExpectedCoin("25187", "Руины Персеполиса", "Серебро", 25.0, null, 7_500.0),
            new ExpectedCoin("24830", "Акрополь", "Серебро", 25.0, null, 7_500.0),
            new ExpectedCoin("24673", "Австралийский опоссум", "Серебро", 25.0, null, 7_500.0),
            new ExpectedCoin("25811", "Полосатый шакал", "Серебро", 20.0, null, 6_000.0),
            new ExpectedCoin("27001", "С пополнением в семье", "Серебро", 20.0, null, 6_000.0),
            new ExpectedCoin("25777", "Всепобеждающая любовь", "Серебро", 20.0, null, 6_000.0));

    // https://www.vtb.ru/personal/vklady-i-scheta/monety-iz-dragotsennyih-metallov/ + BFF coinKind=Инвестиционные, query=по
    @Test
    void poInvestmentCatalog() {
        ScrapePayload<Coin> payload =
                new VtbScraper().scrape("по", true, null);
        assertCatalog(payload, PO_EXPECTED, 3, 1, 1);
    }

    // https://www.vtb.ru/personal/vklady-i-scheta/monety-iz-dragotsennyih-metallov/, query=по (фильтр локально)
    @Test
    void poCatalog() {
        ScrapePayload<Coin> payload =
                new VtbScraper().scrape("по", false, null);
        List<ExpectedCoin> expected =
                Stream.concat(PO_EXPECTED.stream(), PO_CATALOG_EXTRA.stream()).toList();
        assertCatalog(payload, expected, 4, 9, null);
        assertThat(payload.pagesProcessed()).isGreaterThan(1);
    }

    private static void assertCatalog(
            ScrapePayload<Coin> payload,
            List<ExpectedCoin> expected,
            int goldCount,
            int silverCount,
            Integer pagesProcessed) {
        List<Coin> coins = payload.coins();

        if (pagesProcessed != null) {
            assertThat(payload.pagesProcessed()).isEqualTo(pagesProcessed);
        }
        assertThat(coins.stream().filter(c -> "Серебро".equals(c.metal()))).hasSize(silverCount);
        assertThat(coins.stream().filter(c -> "Золото".equals(c.metal()))).hasSize(goldCount);

        assertThat(coins).hasSize(expected.size());
        for (ExpectedCoin exp : expected) {
            Coin coin = findCoin(coins, exp);
            assertThat(coin.name()).isEqualTo(exp.name());
            assertThat(coin.metal()).isEqualTo(exp.metal());
            assertThat(coin.weightG()).isEqualTo(exp.weightG());
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
            String catalogNumber, String name, String metal, double weightG, Double buyPrice, Double sellPrice) {}
}
