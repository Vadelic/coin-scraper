package ru.scraper.coincatalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.NotImplementedScraper;
import ru.scraper.coincatalog.scraper.ScraperRegistry;
import ru.scraper.coincatalog.scraper.common.HttpFetcher;
import ru.scraper.coincatalog.scraper.goldenplata.GoldenplataPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.goldenplata.GoldenplataScraper;
import ru.scraper.coincatalog.scraper.lanta.LantaPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.lanta.LantaScraper;
import ru.scraper.coincatalog.scraper.rshb.RshbPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.rshb.RshbScraper;
import ru.scraper.coincatalog.scraper.atb.AtbPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.atb.AtbScraper;
import ru.scraper.coincatalog.scraper.aurumex.AurumexPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.aurumex.AurumexScraper;
import ru.scraper.coincatalog.scraper.sberbank.SberbankApiClient;
import ru.scraper.coincatalog.scraper.sberbank.SberbankScraper;
import ru.scraper.coincatalog.scraper.vtb.VtbPlaywrightFetcher;
import ru.scraper.coincatalog.scraper.vtb.VtbScraper;
import ru.scraper.coincatalog.scraper.zolotmd.ZolotoMdCatalogFetcher;
import ru.scraper.coincatalog.scraper.zolotmd.ZolotoMdScraper;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class ScraperAutoConfiguration {

    private static final List<String> STUB_SLUGS = List.of();

    @Bean
    HttpFetcher httpFetcher() {
        return HttpFetcher.defaults();
    }

    @Bean
    ZolotoMdCatalogFetcher zolotoMdCatalogFetcher(HttpFetcher httpFetcher) {
        return new ZolotoMdCatalogFetcher(httpFetcher);
    }

    @Bean
    ZolotoMdScraper zolotoMdScraper(ZolotoMdCatalogFetcher fetcher) {
        return new ZolotoMdScraper(fetcher);
    }

    @Bean
    SberbankApiClient sberbankApiClient() {
        return new SberbankApiClient();
    }

    @Bean
    SberbankScraper sberbankScraper(SberbankApiClient apiClient) {
        return new SberbankScraper(apiClient);
    }

    @Bean
    VtbPlaywrightFetcher vtbPlaywrightFetcher() {
        return new VtbPlaywrightFetcher();
    }

    @Bean
    VtbScraper vtbScraper(VtbPlaywrightFetcher fetcher) {
        return new VtbScraper(fetcher);
    }

    @Bean
    AurumexPlaywrightFetcher aurumexPlaywrightFetcher() {
        return new AurumexPlaywrightFetcher();
    }

    @Bean
    AurumexScraper aurumexScraper(AurumexPlaywrightFetcher fetcher) {
        return new AurumexScraper(fetcher);
    }

    @Bean
    AtbPlaywrightFetcher atbPlaywrightFetcher() {
        return new AtbPlaywrightFetcher();
    }

    @Bean
    AtbScraper atbScraper(AtbPlaywrightFetcher fetcher) {
        return new AtbScraper(fetcher);
    }

    @Bean
    GoldenplataPlaywrightFetcher goldenplataPlaywrightFetcher() {
        return new GoldenplataPlaywrightFetcher();
    }

    @Bean
    GoldenplataScraper goldenplataScraper(GoldenplataPlaywrightFetcher fetcher) {
        return new GoldenplataScraper(fetcher);
    }

    @Bean
    LantaPlaywrightFetcher lantaPlaywrightFetcher() {
        return new LantaPlaywrightFetcher();
    }

    @Bean
    LantaScraper lantaScraper(LantaPlaywrightFetcher fetcher) {
        return new LantaScraper(fetcher);
    }

    @Bean
    RshbPlaywrightFetcher rshbPlaywrightFetcher() {
        return new RshbPlaywrightFetcher();
    }

    @Bean
    RshbScraper rshbScraper(RshbPlaywrightFetcher fetcher) {
        return new RshbScraper(fetcher);
    }

    @Bean
    List<CoinScraper> coinScrapers(
            ZolotoMdScraper zolotoMdScraper,
            SberbankScraper sberbankScraper,
            VtbScraper vtbScraper,
            AurumexScraper aurumexScraper,
            AtbScraper atbScraper,
            GoldenplataScraper goldenplataScraper,
            LantaScraper lantaScraper,
            RshbScraper rshbScraper) {
        List<CoinScraper> scrapers = new ArrayList<>();
        scrapers.add(zolotoMdScraper);
        scrapers.add(sberbankScraper);
        scrapers.add(vtbScraper);
        scrapers.add(aurumexScraper);
        scrapers.add(atbScraper);
        scrapers.add(goldenplataScraper);
        scrapers.add(lantaScraper);
        scrapers.add(rshbScraper);
        for (String slug : STUB_SLUGS) {
            scrapers.add(new NotImplementedScraper(slug));
        }
        return scrapers;
    }

    @Bean
    ScraperRegistry scraperRegistry(List<CoinScraper> coinScrapers) {
        return new ScraperRegistry(coinScrapers);
    }
}
