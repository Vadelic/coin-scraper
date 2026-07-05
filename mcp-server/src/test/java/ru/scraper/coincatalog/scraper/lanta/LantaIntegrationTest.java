package ru.scraper.coincatalog.scraper.lanta;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.common.ScrapePayload;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
@EnabledIf("lantaSessionAvailable")
class LantaIntegrationTest {

    private static final List<ExpectedCoin> PO_EXPECTED = List.of(
            new ExpectedCoin("5215-0036", null, "Золото", 3.11, 37_900.0, null),
            new ExpectedCoin("5216-0060", "СПМД", "Золото", 7.78, 81_500.0, 93_500.0),
            new ExpectedCoin("5216-0060", "ММД", "Золото", 7.78, 83_000.0, 94_000.0),
            new ExpectedCoin("5217-0048", null, "Золото", 15.55, 166_000.0, 203_500.0),
            new ExpectedCoin("5219-0033", null, "Золото", 31.1, 339_000.0, null),
            new ExpectedCoin("5111-0178", null, "Серебро", 31.1, 5_700.0, 6_900.0));

    static boolean lantaSessionAvailable() {
        String env = System.getenv("LANTA_STORAGE_STATE");
        if (env != null && !env.isBlank()) {
            return Files.isRegularFile(Path.of(env.strip()));
        }
        return LantaScraperSettings.defaults().resolvedStorageStatePath().isPresent();
    }

    @Test
    void poInvestmentCatalog() {
        ScrapePayload<Coin> payload =
                new LantaScraper(LantaScraperSettings.defaults()).scrape("по", true, null);
        assertInvestmentCatalog(payload, PO_EXPECTED, 5, 1);
    }

    private static void assertInvestmentCatalog(
            ScrapePayload<Coin> payload, List<ExpectedCoin> expected, int goldCount, int silverCount) {
        List<Coin> coins = payload.coins();

        assertThat(payload.pagesProcessed()).isEqualTo(1);
        assertThat(coins).hasSize(expected.size());
        assertThat(coins.stream().filter(c -> "Серебро".equals(c.metal()))).hasSize(silverCount);
        assertThat(coins.stream().filter(c -> "Золото".equals(c.metal()))).hasSize(goldCount);

        for (ExpectedCoin exp : expected) {
            Coin coin = findCoin(coins, exp);
            assertThat(coin.name()).isNotBlank();
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
                .filter(c -> expected.nameContains() == null || c.name().contains(expected.nameContains()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Coin not found: " + expected));
    }

    private record ExpectedCoin(
            String catalogNumber, String nameContains, String metal, double weightG, Double buyPrice, Double sellPrice) {}
}
