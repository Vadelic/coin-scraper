package ru.scraper.coincatalog.scraper.zolotmd;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.common.HttpFetcher;
import ru.scraper.coincatalog.scraper.common.ScrapePayload;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Скрапер каталога zoloto-md.ru через HTTP. */
@Slf4j
@Service("ZOLOTO_MD")
@RequiredArgsConstructor
public class ZolotoMdScraper implements CoinScraper<Coin> {

    private static final int DEFAULT_LIMIT = 100;

    private final HttpFetcher httpFetcher;

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";

        // Warm-up: сайт отдаёт необработанный MODX-шаблон без cookies с главной.
        httpFetcher.fetchText(ZolotoMdPageParser.BASE_URL + "/");

        String firstUrl = ZolotoMdPageParser.buildCatalogUrl(1, DEFAULT_LIMIT, normalizedQuery, investmentOnly);
        log.info("Loading first page: {}", firstUrl);
        String firstHtml = httpFetcher.fetchText(firstUrl);

        int totalPages = ZolotoMdPageParser.parseTotalPages(firstHtml, 1);
        log.info("Pages to fetch: {}", totalPages);

        var allParsed = new ArrayList<ZolotoMdPageParser.ParsedCoin>();
        var seenUrls = new HashSet<String>();

        for (int page = 1; page <= totalPages; page++) {
            String html = page == 1 ? firstHtml : loadPage(page, normalizedQuery, investmentOnly);
            var pageCoins = ZolotoMdPageParser.parseCoins(html);
            int added = 0;
            for (var parsed : pageCoins) {
                if (seenUrls.add(parsed.url())) {
                    allParsed.add(parsed);
                    added++;
                }
            }
            log.info("Page {}: found {}, added {}", page, pageCoins.size(), added);
        }

        List<Coin> coins = allParsed.stream().map(ZolotoMdPageParser.ParsedCoin::coin).toList();
        if (coins.isEmpty() && normalizedQuery.isBlank()) {
            throw new IllegalStateException("Каталог пуст");
        }
        return new ScrapePayload(totalPages, coins);
    }

    private String loadPage(int page, String query, boolean investmentOnly) {
        String url = ZolotoMdPageParser.buildCatalogUrl(page, DEFAULT_LIMIT, query, investmentOnly);
        log.info("Loading page {}: {}", page, url);
        return httpFetcher.fetchText(url);
    }
}
