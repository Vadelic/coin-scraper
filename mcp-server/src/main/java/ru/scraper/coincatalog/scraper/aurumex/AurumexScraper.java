package ru.scraper.coincatalog.scraper.aurumex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.support.HttpScrapeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Скрапер каталога Aurumex через HTTP и Nuxt {@code _payload.json}. */
@Slf4j
@Service("AURUMEX")
public class AurumexScraper implements CoinScraper<Coin> {

    private final HttpScrapeClient http;
    private final ObjectMapper objectMapper;
    private final int retries;
    private final double delaySeconds;
    private final int maxPages;

    public AurumexScraper() {
        this(HttpScrapeClient.defaults(), 3, 0.35, AurumexPayloadParser.DEFAULT_MAX_PAGES);
    }

    AurumexScraper(HttpScrapeClient http, int retries, double delaySeconds, int maxPages) {
        this.http = http;
        this.objectMapper = new ObjectMapper();
        this.retries = Math.max(1, retries);
        this.delaySeconds = Math.max(0, delaySeconds);
        this.maxPages = maxPages;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";
        Map<String, Boolean> seenKeys = new LinkedHashMap<>();
        List<JsonNode> stores = new ArrayList<>();
        int pagesProcessed = 0;

        log.info("Открываю каталог: {}", AurumexPayloadParser.CATALOG_URL);
        String catalogHtml = http.getText(
                AurumexPayloadParser.CATALOG_URL,
                HttpScrapeClient.htmlGetHeaders(AurumexPayloadParser.PUBLIC_URL + "/"));
        if (isCaptchaHtml(catalogHtml)) {
            throw new CaptchaBlockedException("CAPTCHA на странице каталога Aurumex");
        }

        for (int pageNum = 1; pageNum <= maxPages; pageNum++) {
            if (pageNum > 1 && delaySeconds > 0) {
                sleep(delaySeconds);
            }
            String url = AurumexPayloadParser.payloadUrlForPage(pageNum);
            JsonNode store = fetchPayloadJson(url);
            if (!store.isArray()) {
                throw new IllegalStateException("Неверный формат payload: " + url);
            }

            var pageCoins = AurumexPayloadParser.extractCoinsFromStore(store);
            pagesProcessed++;
            int before = seenKeys.size();

            for (var coin : pageCoins) {
                String key = AurumexPayloadParser.dedupeKey(coin);
                seenKeys.putIfAbsent(key, Boolean.TRUE);
            }

            stores.add(store);
            int added = seenKeys.size() - before;
            log.info(
                    "Страница {}: в payload={}, добавлено={}, всего={}",
                    pageNum,
                    pageCoins.size(),
                    added,
                    seenKeys.size());

            if (added == 0 && !seenKeys.isEmpty()) {
                log.info("Остановка: новые монеты на странице {} не добавились", pageNum);
                break;
            }
        }

        List<Coin> coins = mergeAndFilter(stores, normalizedQuery);
        if (!normalizedQuery.isEmpty()) {
            log.info("После фильтра query=«{}»: {} монет", normalizedQuery, coins.size());
        }
        return new ScrapePayload<>(pagesProcessed, coins);
    }

    List<Coin> mergeAndFilter(List<JsonNode> stores, String query) {
        Map<String, Coin> byKey = new LinkedHashMap<>();
        for (JsonNode store : stores) {
            for (Coin coin : AurumexPayloadParser.extractCoinsFromStore(store)) {
                String key = AurumexPayloadParser.dedupeKey(coin);
                if (byKey.containsKey(key)) {
                    continue;
                }
                if (!AurumexPayloadParser.coinMatchesQuery(coin, query)) {
                    continue;
                }
                if (coin.sellPrice() == null) {
                    log.warn(
                            "Нет sell_price для «{}» (артикул {})",
                            coin.name(),
                            coin.catalogNumber());
                }
                byKey.put(key, coin);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private JsonNode fetchPayloadJson(String url) {
        Exception lastError = null;
        Map<String, String> headers = HttpScrapeClient.jsonHeaders(
                AurumexPayloadParser.PUBLIC_URL, AurumexPayloadParser.CATALOG_URL, false);

        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                log.info("Загрузка payload: {} (попытка {}/{})", url, attempt, retries);
                String text = http.getText(url, headers);
                return objectMapper.readTree(text);
            } catch (Exception e) {
                lastError = e;
                log.warn("payload {}: {}", url, e.getMessage());
                if (attempt < retries) {
                    sleep(delaySeconds * Math.pow(2, attempt - 1));
                }
            }
        }
        throw new IllegalStateException("Не удалось загрузить " + url + ": " + lastError);
    }

    private static boolean isCaptchaHtml(String html) {
        if (html == null) {
            return false;
        }
        String low = html.toLowerCase();
        int t0 = low.indexOf("<title");
        if (t0 >= 0) {
            int t1 = html.indexOf('>', t0);
            int t2 = low.indexOf("</title>", t1);
            if (t1 > 0 && t2 > t1) {
                if (AurumexPayloadParser.isCaptchaTitle(html.substring(t1 + 1, t2))) {
                    return true;
                }
            }
        }
        String snippet = html.length() > 4000 ? html.substring(0, 4000) : html;
        return AurumexPayloadParser.isCaptchaBody(snippet);
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }
}
