package ru.scraper.coincatalog.mcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.aurumex.AurumexScraper;
import ru.scraper.coincatalog.scraper.common.ScrapePayload;
import ru.scraper.coincatalog.scraper.lanta.LantaScraper;
import ru.scraper.coincatalog.scraper.rshb.RshbScraper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest
class CoinCatalogToolsTest {

    @Autowired
    private CoinCatalogTools tools;

    @MockitoBean
    private AurumexScraper aurumexScraper;

    @MockitoBean
    private LantaScraper lantaScraper;

    @MockitoBean
    private RshbScraper rshbScraper;

    @Test
    void rshbToolReturnsCoins() {
        when(rshbScraper.scrape("золото", true, "77"))
                .thenReturn(new ScrapePayload(
                        1,
                        List.of(new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 85000.0, 107000.0))));

        List<Coin> coins = tools.scrapeRshb("золото", true, null);

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
        assertThat(coins.get(0).sellPrice()).isEqualTo(107000.0);
    }

    @Test
    void lantaToolReturnsCoins() {
        when(lantaScraper.scrape("победоносец", true, null))
                .thenReturn(new ScrapePayload(
                        1,
                        List.of(new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 99700.0))));

        List<Coin> coins = tools.scrapeLanta("победоносец", true);

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).name()).contains("Победоносец");
        assertThat(coins.get(0).sellPrice()).isEqualTo(99700.0);
    }

    @Test
    void aurumexToolReturnsFilteredCoins() {
        when(aurumexScraper.scrape("победоносец", false, null))
                .thenReturn(new ScrapePayload(
                        1,
                        List.of(new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, null, 99700.0))));

        List<Coin> coins = tools.scrapeAurumex("победоносец");

        assertThat(coins).hasSize(1);
        assertThat(coins.get(0).catalogNumber()).isEqualTo("5216-0060");
    }
}
