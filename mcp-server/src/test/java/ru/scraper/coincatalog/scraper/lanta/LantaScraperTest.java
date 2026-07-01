package ru.scraper.coincatalog.scraper.lanta;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeStatus;
import ru.scraper.coincatalog.scraper.CaptchaBlockedException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LantaScraperTest {

    @Mock
    private LantaPlaywrightFetcher fetcher;

    @InjectMocks
    private LantaScraper scraper;

    @Test
    void returnsOkWithCoins() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new LantaPlaywrightFetcher.FetchResult(
                        1,
                        List.of(
                                new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 85000.0, 99700.0),
                                new Coin("5111-0008", "Серебряная монета", "Серебро", 31.1, null, 3500.0))));

        var result = scraper.scrape(ScrapeRequest.of("победоносец", true, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.totalCoins()).isEqualTo(2);
        assertThat(result.query()).isEqualTo("победоносец");
        assertThat(result.investmentOnly()).isTrue();
    }

    @Test
    void zeroPagesIsError() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenReturn(new LantaPlaywrightFetcher.FetchResult(0, List.of()));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.ERROR);
        assertThat(result.error()).contains("Не удалось загрузить каталог");
    }

    @Test
    void captchaBlockedReturnsStatus() {
        when(fetcher.fetchCatalog(anyString(), anyBoolean()))
                .thenThrow(new CaptchaBlockedException("CAPTCHA"));

        var result = scraper.scrape(ScrapeRequest.of(null, false, null));

        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.CAPTCHA_BLOCKED);
    }
}
