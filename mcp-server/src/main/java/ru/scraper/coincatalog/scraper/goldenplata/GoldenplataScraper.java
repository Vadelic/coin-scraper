package ru.scraper.coincatalog.scraper.goldenplata;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.support.HttpScrapeClient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Скрапер каталога Goldenplata через HTTP (analytics JSON в HTML). */
@Slf4j
@Service("GOLDENPLATA")
public class GoldenplataScraper implements CoinScraper<Coin> {

    private final HttpScrapeClient http;
    private final int retries;
    private final double delaySeconds;
    private final int maxPages;
    private final Function<PageFetchRequest, String> pageHtmlOverride;

    public GoldenplataScraper() {
        this(HttpScrapeClient.defaults(), null);
    }

    GoldenplataScraper(Function<PageFetchRequest, String> pageHtmlOverride) {
        this(HttpScrapeClient.defaults(), pageHtmlOverride);
    }

    GoldenplataScraper(HttpScrapeClient http, Function<PageFetchRequest, String> pageHtmlOverride) {
        this(http, 3, 0.35, 0, pageHtmlOverride);
    }

    GoldenplataScraper(
            HttpScrapeClient http,
            int retries,
            double delaySeconds,
            int maxPages,
            Function<PageFetchRequest, String> pageHtmlOverride) {
        this.http = http;
        this.retries = Math.max(1, retries);
        this.delaySeconds = Math.max(0, delaySeconds);
        this.maxPages = maxPages;
        this.pageHtmlOverride = pageHtmlOverride;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String catalogBase = GoldenplataPageParser.resolveCatalogBase(investmentOnly);
        String normalizedQuery = query != null ? query.strip() : "";
        List<String> htmlPages = new ArrayList<>();

        String firstUrl = GoldenplataPageParser.buildCatalogUrl(catalogBase, 1, normalizedQuery);
        String firstHtml = fetchPageHtml(firstUrl, 1);
        if (GoldenplataPageParser.isCaptchaTitle(extractTitle(firstHtml))
                || GoldenplataPageParser.isCaptchaBody(firstHtml)
                || GoldenplataPageParser.isCaptchaInterstitial(firstHtml)) {
            throw new CaptchaBlockedException(
                    "goldenplata.ru показал CAPTCHA вместо каталога.");
        }

        int pagesToVisit = GoldenplataPageParser.parseTotalPages(firstHtml);
        if (maxPages > 0) {
            pagesToVisit = Math.min(pagesToVisit, maxPages);
        }
        if (pagesToVisit < 1) {
            pagesToVisit = 1;
        }
        log.info("Страниц каталога: {}", pagesToVisit);

        htmlPages.add(firstHtml);
        log.info("Страница 1: монет в analytics={}", GoldenplataPageParser.parseCoinsFromHtml(firstHtml).size());

        for (int pageNum = 2; pageNum <= pagesToVisit; pageNum++) {
            if (delaySeconds > 0) {
                sleep(delaySeconds);
            }
            String url = GoldenplataPageParser.buildCatalogUrl(catalogBase, pageNum, normalizedQuery);
            String html = fetchPageHtml(url, pageNum);
            htmlPages.add(html);
            log.info(
                    "Страница {}: монет в analytics={}",
                    pageNum,
                    GoldenplataPageParser.parseCoinsFromHtml(html).size());
        }

        return new ScrapePayload<>(htmlPages.size(), mergeCoins(htmlPages));
    }

    List<Coin> mergeCoins(List<String> pageHtmls) {
        Map<String, Coin> byKey = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();

        for (String html : pageHtmls) {
            for (Coin coin : GoldenplataPageParser.parseCoinsFromHtml(html)) {
                String key = GoldenplataPageParser.dedupeKey(coin, coin.catalogNumber());
                if (!seen.add(key)) {
                    log.warn(
                            "Дубликат (ключ {}): «{}», id={}",
                            key,
                            coin.name(),
                            coin.catalogNumber());
                    continue;
                }
                if (coin.sellPrice() == null) {
                    log.warn("Нет цены продажи: «{}»", coin.name());
                }
                byKey.put(key, coin);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private String fetchPageHtml(String url, int pageNum) {
        if (pageHtmlOverride != null) {
            return pageHtmlOverride.apply(new PageFetchRequest(url, pageNum));
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                log.info("Открываю {} (попытка {}/{})", url, attempt, retries);
                return http.getText(url, HttpScrapeClient.htmlGetHeaders(GoldenplataPageParser.BASE_URL + "/"));
            } catch (Exception e) {
                lastError = e;
                log.warn("Навигация: попытка {}/{} — {}", attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(Math.pow(2, attempt));
                }
            }
        }
        throw new IllegalStateException("Не удалось загрузить " + url + ": " + lastError);
    }

    private static String extractTitle(String html) {
        if (html == null) {
            return "";
        }
        String low = html.toLowerCase();
        int t0 = low.indexOf("<title");
        if (t0 < 0) {
            return "";
        }
        int t1 = html.indexOf('>', t0);
        int t2 = low.indexOf("</title>", t1);
        if (t1 < 0 || t2 < 0) {
            return "";
        }
        return html.substring(t1 + 1, t2);
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }

    public record PageFetchRequest(String url, int pageNum) {}
}
