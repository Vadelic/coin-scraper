package ru.scraper.coincatalog.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeSource;
import ru.scraper.coincatalog.model.ScrapeStatus;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.support.ScraperTestSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScrapeRegistryRunTest {

    @Mock
    private CoinScraper<Coin> coinScraper;

    @Mock
    private ApplicationContext applicationContext;

    private ScrapeRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new ScrapeRegistry(List.of(), applicationContext);
    }

    @Test
    void returnsOkWithCoins() throws Exception {
        when(coinScraper.scrape(anyString(), anyBoolean(), nullable(String.class)))
                .thenReturn(new ScrapePayload(
                        2,
                        List.of(new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 85000.0, 99700.0))));

        var result = registry.run(coinScraper, ScrapeRequest.of("победоносец", true, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(2);
        assertThat(result.totalCoins()).isOne();
        ScraperTestSupport.assertOkWithCoins(result);
    }

    @Test
    void zeroPagesAndNoCoinsIsError() {
        when(coinScraper.scrape(anyString(), anyBoolean(), nullable(String.class)))
                .thenReturn(new ScrapePayload(0, List.of()));

        var result = registry.run(coinScraper, ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).isEqualTo("Не удалось загрузить каталог");
    }

    @Test
    void fetchFailureReturnsError() {
        when(coinScraper.scrape(anyString(), anyBoolean(), nullable(String.class)))
                .thenThrow(new IllegalStateException("network down"));

        var result = registry.run(coinScraper, ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("network down");
    }

    @Test
    void captchaBlockedReturnsStatus() {
        when(coinScraper.scrape(anyString(), anyBoolean(), nullable(String.class)))
                .thenThrow(new CaptchaBlockedException("CAPTCHA"));

        var result = registry.run(coinScraper, ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.CAPTCHA_BLOCKED);
        assertThat(result.error()).contains("CAPTCHA");
    }

    @Test
    void fetcherExceptionMessageIsReturned() {
        when(coinScraper.scrape(anyString(), anyBoolean(), nullable(String.class)))
                .thenThrow(new IllegalStateException("Каталог пуст"));

        var result = registry.run(coinScraper, ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).isEqualTo("Каталог пуст");
    }

    @Test
    void unregisteredSourceReturnsNotImplemented() {
        var result = registry.run(ScrapeSource.ATB, ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).isEqualTo("not implemented");
    }
}
