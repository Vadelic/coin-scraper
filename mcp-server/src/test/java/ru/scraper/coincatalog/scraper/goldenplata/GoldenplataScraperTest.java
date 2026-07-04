package ru.scraper.coincatalog.scraper.goldenplata;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class GoldenplataScraperTest {

    private final GoldenplataScraper fetcher = new GoldenplataScraper();

    @Test
    void mergesCoinsFromPages() throws Exception {
        List<Coin> coins = fetcher.mergeCoins(
                List.of(loadFixture("catalog_page1.html"), loadFixture("catalog_page2.html")));

        assertThat(coins).hasSize(4);
    }

    @Test
    void deduplicatesAcrossPages() throws Exception {
        List<Coin> coins = fetcher.mergeCoins(
                List.of(loadFixture("catalog_page1.html"), loadFixture("catalog_page1.html")));

        assertThat(coins).hasSize(3);
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = GoldenplataScraperTest.class.getResourceAsStream("/fixtures/goldenplata/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
