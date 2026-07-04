package ru.scraper.coincatalog.scraper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ScraperRegistryTest {

    private static final List<String> EXPECTED_SLUGS = List.of(
            "zoloto-md", "sberbank", "vtb", "aurumex", "atb", "goldenplata", "lanta", "rshb");

    @Autowired
    private ScraperRegistry registry;

    @Autowired
    private List<CoinScraper> scrapers;

    @Test
    void registersAllEightScrapers() {
        assertThat(scrapers).hasSize(8);
        assertThat(scrapers.stream().map(CoinScraper::slug).sorted()).containsExactlyElementsOf(
                EXPECTED_SLUGS.stream().sorted().toList());
    }

    @Test
    void resolvesEachSlug() {
        for (String slug : EXPECTED_SLUGS) {
            assertThat(registry.get(slug).slug()).isEqualTo(slug);
        }
    }
}
