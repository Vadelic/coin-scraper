package ru.scraper.coincatalog.scraper;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.atb.AtbScraper;
import ru.scraper.coincatalog.scraper.aurumex.AurumexScraper;
import ru.scraper.coincatalog.scraper.goldenplata.GoldenplataScraper;
import ru.scraper.coincatalog.scraper.lanta.LantaScraper;
import ru.scraper.coincatalog.scraper.rshb.RshbScraper;
import ru.scraper.coincatalog.scraper.sberbank.SberbankScraper;
import ru.scraper.coincatalog.scraper.vtb.VtbScraper;
import ru.scraper.coincatalog.scraper.zolotmd.ZolotoMdScraper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live smoke: query «побед» must return ≥1 coin for every scraper.
 * Run: {@code ./mvnw test -Dtest=AllScrapersPobedLiveTest -Dsurefire.excludedGroups=}
 */
@Tag("live")
class AllScrapersPobedLiveTest {

    private static final String QUERY = "побед";

    @Test
    void eachScraperFindsAtLeastOneCoin() {
        Map<String, Supplier<CoinScraper.ScrapePayload<Coin>>> scrapers = new LinkedHashMap<>();
        scrapers.put("SBERBANK", () -> new SberbankScraper().scrape(QUERY, true, null));
        scrapers.put("ZOLOTO_MD", () -> new ZolotoMdScraper(new ru.scraper.coincatalog.scraper.zolotmd.HttpFetcher()).scrape(QUERY, true, null));
        scrapers.put("VTB", () -> new VtbScraper().scrape(QUERY, true, null));
        scrapers.put("AURUMEX", () -> new AurumexScraper().scrape(QUERY, true, null));
        scrapers.put("ATB", () -> new AtbScraper().scrape(QUERY, true, null));
        scrapers.put("GOLDENPLATA", () -> new GoldenplataScraper().scrape(QUERY, true, null));
        scrapers.put("LANTA", () -> new LantaScraper().scrape(QUERY, true, null));
        scrapers.put("RSHB", () -> new RshbScraper().scrape(QUERY, true, null));

        List<String> failures = new ArrayList<>();
        for (Map.Entry<String, Supplier<CoinScraper.ScrapePayload<Coin>>> entry : scrapers.entrySet()) {
            String name = entry.getKey();
            try {
                long t0 = System.currentTimeMillis();
                CoinScraper.ScrapePayload<Coin> payload = entry.getValue().get();
                long ms = System.currentTimeMillis() - t0;
                int count = payload.coins() == null ? 0 : payload.coins().size();
                System.out.printf("%s: coins=%d pages=%d %dms%n", name, count, payload.pagesProcessed(), ms);
                if (count < 1) {
                    failures.add(name + ": coins=" + count);
                } else {
                    System.out.printf("  sample: %s%n", payload.coins().getFirst().name());
                }
            } catch (CaptchaBlockedException e) {
                System.out.printf("%s: CAPTCHA_BLOCKED — %s%n", name, e.getMessage());
                failures.add(name + ": CAPTCHA_BLOCKED");
            } catch (Exception e) {
                System.out.printf("%s: ERROR — %s%n", name, e.getMessage());
                failures.add(name + ": ERROR " + e.getMessage());
            }
        }

        assertThat(failures)
                .as("scrapers that failed to return ≥1 coin for query «%s»: %s", QUERY, failures)
                .isEmpty();
    }
}
