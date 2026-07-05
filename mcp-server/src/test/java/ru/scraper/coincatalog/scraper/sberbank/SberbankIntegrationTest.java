package ru.scraper.coincatalog.scraper.sberbank;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.common.ScrapePayload;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class SberbankIntegrationTest {

    private static final Map<String, ExpectedCoin> POBEDON_EXPECTED = Map.of(
            "5215-0036", new ExpectedCoin("Золото", 3.11, 31_000.0, 53_570.0),
            "5216-0060", new ExpectedCoin("Золото", 7.78, 77_000.0, 123_030.0),
            "5217-0048", new ExpectedCoin("Золото", 15.55, 154_000.0, 238_770.0),
            "5219-0033", new ExpectedCoin("Золото", 31.1, 308_000.0, 463_070.0),
            "5111-0178", new ExpectedCoin("Серебро", 31.1, 5_700.0, 9_500.0));

    private static final Map<String, ExpectedCoin> INVESTMENT_CATALOG_EXPECTED = Map.ofEntries(
            Map.entry("3213-0007", new ExpectedCoin("Золото", 7.74, 77_000.0, 123_030.0)),
            Map.entry("5111-0033", new ExpectedCoin("Серебро", 31.1, 5_700.0, 9_500.0)),
            Map.entry("5111-0178", new ExpectedCoin("Серебро", 31.1, 5_700.0, 9_500.0)),
            Map.entry("5214-0009", new ExpectedCoin("Золото", 7.78, 77_000.0, 123_030.0)),
            Map.entry("5215-0036", new ExpectedCoin("Золото", 3.11, 31_000.0, 53_570.0)),
            Map.entry("5216-0060", new ExpectedCoin("Золото", 7.78, 77_000.0, 123_030.0)),
            Map.entry("5216-0080", new ExpectedCoin("Золото", 7.78, 77_000.0, 123_030.0)),
            Map.entry("5216-0089", new ExpectedCoin("Золото", 7.78, 77_000.0, 123_030.0)),
            Map.entry("5217-0038", new ExpectedCoin("Золото", 15.55, 154_000.0, 238_770.0)),
            Map.entry("5217-0040", new ExpectedCoin("Золото", 15.55, 154_000.0, 238_770.0)),
            Map.entry("5217-0048", new ExpectedCoin("Золото", 15.55, 154_000.0, 238_770.0)),
            Map.entry("5219-0033", new ExpectedCoin("Золото", 31.1, 308_000.0, 463_070.0)));

    @Test
    void pobedonInvestmentCatalog() {
        assertInvestmentCatalog(new SberbankScraper().scrape("победон", true, null), POBEDON_EXPECTED, 4, 1);
    }

    @Test
    void investmentCatalogWithoutQuery() {
        assertInvestmentCatalog(new SberbankScraper().scrape(null, true, null), INVESTMENT_CATALOG_EXPECTED, 10, 2);
    }

    private static void assertInvestmentCatalog(
            ScrapePayload<Coin> payload, Map<String, ExpectedCoin> expected, int goldCount, int silverCount) {
        List<Coin> coins = payload.coins();

        assertThat(payload.pagesProcessed()).isEqualTo(SberbankResponseParser.DEFAULT_METAL_FILTERS.size());
        assertThat(coins).hasSize(expected.size());
        assertThat(coins.stream().filter(c -> "Серебро".equals(c.metal()))).hasSize(silverCount);
        assertThat(coins.stream().filter(c -> "Золото".equals(c.metal()))).hasSize(goldCount);

        Map<String, Coin> byCatalog = coins.stream()
                .collect(Collectors.toMap(Coin::catalogNumber, Function.identity()));

        assertThat(byCatalog.keySet()).containsExactlyInAnyOrderElementsOf(expected.keySet());

        for (var entry : expected.entrySet()) {
            Coin coin = byCatalog.get(entry.getKey());
            ExpectedCoin exp = entry.getValue();

            assertThat(coin.name()).isNotBlank();
            assertThat(coin.metal()).isEqualTo(exp.metal());
            assertThat(coin.weightG()).isEqualTo(exp.weightG());
            assertThat(coin.buyPrice()).isEqualTo(exp.buyPrice());
            assertThat(coin.sellPrice()).isEqualTo(exp.sellPrice());
            assertThat(coin.buyPrice()).isLessThan(coin.sellPrice());
        }
    }

    private record ExpectedCoin(String metal, double weightG, double buyPrice, double sellPrice) {}
}
