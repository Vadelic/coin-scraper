package ru.scraper.coincatalog.scraper.zolotmd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.scraper.common.HttpFetcher;

@Service
public class ZolotoMdCatalogFetcher {

    private static final Logger log = LoggerFactory.getLogger(ZolotoMdCatalogFetcher.class);
    private static final int DEFAULT_LIMIT = 100;

    private final HttpFetcher httpFetcher;

    public ZolotoMdCatalogFetcher(HttpFetcher httpFetcher) {
        this.httpFetcher = httpFetcher;
    }

    public FetchResult fetchAllPages(String query, boolean investmentOnly) {
        // Warm-up: сайт отдаёт необработанный MODX-шаблон без cookies с главной.
        httpFetcher.fetchText(ZolotoMdPageParser.BASE_URL + "/");

        String firstUrl = ZolotoMdPageParser.buildCatalogUrl(1, DEFAULT_LIMIT, query, investmentOnly);
        log.info("Loading first page: {}", firstUrl);
        String firstHtml = httpFetcher.fetchText(firstUrl);

        int totalPages = ZolotoMdPageParser.parseTotalPages(firstHtml, 1);
        log.info("Pages to fetch: {}", totalPages);

        var allCoins = new java.util.ArrayList<ZolotoMdPageParser.ParsedCoin>();
        var seenUrls = new java.util.HashSet<String>();

        for (int page = 1; page <= totalPages; page++) {
            String html = page == 1 ? firstHtml : loadPage(page, query, investmentOnly);
            var pageCoins = ZolotoMdPageParser.parseCoins(html);
            int added = 0;
            for (var parsed : pageCoins) {
                if (seenUrls.add(parsed.url())) {
                    allCoins.add(parsed);
                    added++;
                }
            }
            log.info("Page {}: found {}, added {}", page, pageCoins.size(), added);
        }

        return new FetchResult(totalPages, allCoins);
    }

    private String loadPage(int page, String query, boolean investmentOnly) {
        String url = ZolotoMdPageParser.buildCatalogUrl(page, DEFAULT_LIMIT, query, investmentOnly);
        log.info("Loading page {}: {}", page, url);
        return httpFetcher.fetchText(url);
    }

    public record FetchResult(int totalPages, java.util.List<ZolotoMdPageParser.ParsedCoin> coins) {}
}
