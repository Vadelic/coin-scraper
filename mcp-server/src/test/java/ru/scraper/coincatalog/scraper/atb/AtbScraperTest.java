package ru.scraper.coincatalog.scraper.atb;

import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AtbScraperTest {

    @Test
    void emptySearchResultReturnsEmptyList() throws Exception {
        ScrapePayload<Coin> result =
                AtbScraper.buildResult(loadFixture("ajax_empty.html"), List.of());

        assertThat(result.pagesProcessed()).isEqualTo(1);
        assertThat(result.coins()).isEmpty();
    }

    @Test
    void emptyWithoutNoResultMarkerThrows() {
        assertThatThrownBy(() -> AtbScraper.buildResult("<div></div>", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("распарсить");
    }

    @Test
    void nonEmptyCoinsReturnsOkPayload() throws Exception {
        List<Coin> coins = sampleCoins();
        ScrapePayload<Coin> result =
                AtbScraper.buildResult(loadFixture("ajax_fragment.html"), coins);

        assertThat(result.coins()).hasSize(2);
    }

    private static List<Coin> sampleCoins() throws Exception {
        List<AtbPageParser.CardMatch> cards =
                AtbPageParser.parseCardsFromFragment(loadFixture("ajax_fragment.html"));
        return List.of(
                AtbPageParser.parseCard(
                                cards.get(0).cardHtml(),
                                cards.get(0).href(),
                                loadFixture("detail_georgiy.html"))
                        .orElseThrow()
                        .coin(),
                AtbPageParser.parseCard(
                                cards.get(1).cardHtml(),
                                cards.get(1).href(),
                                loadFixture("detail_silver.html"))
                        .orElseThrow()
                        .coin());
    }

    private static String loadFixture(String name) throws Exception {
        try (var in = AtbScraperTest.class.getResourceAsStream("/fixtures/atb/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
