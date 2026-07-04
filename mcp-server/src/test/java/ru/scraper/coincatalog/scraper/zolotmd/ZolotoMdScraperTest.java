package ru.scraper.coincatalog.scraper.zolotmd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.scraper.coincatalog.scraper.common.HttpFetcher;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ZolotoMdScraperTest {

    @Mock
    private HttpFetcher httpFetcher;

    @InjectMocks
    private ZolotoMdScraper fetcher;

    @Test
    void emptyCatalogWithoutQueryThrows() {
        when(httpFetcher.fetchText(anyString())).thenReturn("<html></html>");

        assertThatThrownBy(() -> fetcher.scrape("", false, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Каталог пуст");
    }
}
