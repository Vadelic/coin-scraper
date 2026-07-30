package ru.scraper.coincatalog.scraper.vtb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.support.HttpScrapeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Скрапер каталога ВТБ через HTTP и BFF API.
 *
 * <p>Поток: GET витрины (cookies / CAPTCHA) → POST {@code /api/bff/api/v1/coin/list?page=N} →
 * {@link VtbBffResponseParser}; {@code query} фильтруется локально.
 */
@Slf4j
@Service("VTB")
public class VtbScraper implements CoinScraper<Coin> {

    private final HttpScrapeClient http;
    private final ObjectMapper objectMapper;
    private final int retries;
    private final double delaySeconds;
    private final Integer maxPages;

    public VtbScraper() {
        this(HttpScrapeClient.defaults(), 3, 0.35, null);
    }

    VtbScraper(HttpScrapeClient http, int retries, double delaySeconds, Integer maxPages) {
        this.http = http;
        this.objectMapper = new ObjectMapper();
        this.retries = Math.max(1, retries);
        this.delaySeconds = delaySeconds;
        this.maxPages = maxPages;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";
        if (!normalizedQuery.isBlank()) {
            log.info("Фильтр query=«{}» (локально по name / catalog_number / metal)", normalizedQuery);
        }
        List<ObjectNode> apiFilters = VtbBffResponseParser.resolveApiFilters(investmentOnly);
        List<JsonNode> allRows = new ArrayList<>();
        int pagesProcessed = 0;

        log.info("Открываю {}", VtbBffResponseParser.COIN_CATALOG_URL);
        String vitrineHtml = http.getText(
                VtbBffResponseParser.COIN_CATALOG_URL,
                HttpScrapeClient.htmlGetHeaders(VtbBffResponseParser.BASE_SITE + "/"));
        if (isCaptchaHtml(vitrineHtml)) {
            throw new CaptchaBlockedException(
                    "ВТБ показал страницу проверки вместо каталога.");
        }

        if (investmentOnly) {
            log.info(
                    "Фильтр BFF: {}={}",
                    VtbBffResponseParser.COIN_KIND_FILTER_ID,
                    VtbBffResponseParser.INVESTMENT_KIND_VALUE);
        }

        int pageNum = 1;
        int maxPageSeen = 1;
        ObjectNode payload = VtbBffResponseParser.buildPayload(apiFilters);

        while (true) {
            if (pageNum > 1 && delaySeconds > 0) {
                sleep(delaySeconds);
            }

            log.info("Запрос списка page={}", pageNum);
            JsonNode body = fetchListPage(pageNum, payload);
            if (body == null) {
                log.error("Страница {}: пустой ответ BFF — останов", pageNum);
                break;
            }

            var parsed = VtbBffResponseParser.parseListResponse(body);
            maxPageSeen = Math.max(maxPageSeen, parsed.maxPage());
            pagesProcessed++;
            allRows.addAll(parsed.coins());

            log.info(
                    "Страница {}/{}: монет в ответе {}, всего сырых {}",
                    pageNum,
                    maxPageSeen,
                    parsed.coins().size(),
                    allRows.size());

            if (maxPages != null && pageNum >= maxPages) {
                break;
            }
            if (pageNum >= maxPageSeen) {
                break;
            }
            pageNum++;
        }

        return new ScrapePayload<>(pagesProcessed, toCoins(allRows, normalizedQuery));
    }

    List<Coin> toCoins(List<JsonNode> rows, String query) {
        List<Coin> coins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            var coinOpt = VtbBffResponseParser.rowToCoin(row);
            if (coinOpt.isEmpty()) {
                continue;
            }
            Coin coin = coinOpt.get();
            if (!VtbBffResponseParser.coinMatchesQuery(coin, query)) {
                continue;
            }
            String key = VtbBffResponseParser.dedupeKey(row, coin);
            if (!seen.add(key)) {
                log.warn("Дубликат (ключ {}): «{}»", key, coin.name());
                continue;
            }
            if (coin.sellPrice() == null) {
                log.warn("Нет sell_price для «{}» (артикул {})", coin.name(), coin.catalogNumber());
            }
            coins.add(coin);
        }
        return coins;
    }

    private JsonNode fetchListPage(int pageNum, ObjectNode payload) {
        String url = VtbBffResponseParser.buildListUrl(pageNum);
        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                byte[] body = objectMapper.writeValueAsBytes(payload);
                String text = http.requestText(
                        "POST",
                        url,
                        body,
                        HttpScrapeClient.jsonHeaders(
                                VtbBffResponseParser.BASE_SITE,
                                VtbBffResponseParser.COIN_CATALOG_URL,
                                true));
                return objectMapper.readTree(text);
            } catch (Exception e) {
                lastError = e;
                log.warn("BFF page={}, попытка {}/{}: {}", pageNum, attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(delaySeconds * Math.pow(2, attempt - 1));
                }
            }
        }
        log.error("BFF page={}: {}", pageNum, lastError != null ? lastError.getMessage() : "");
        return null;
    }

    private static boolean isCaptchaHtml(String html) {
        if (html == null) {
            return false;
        }
        String title = "";
        int t0 = html.toLowerCase().indexOf("<title");
        if (t0 >= 0) {
            int t1 = html.indexOf('>', t0);
            int t2 = html.toLowerCase().indexOf("</title>", t1);
            if (t1 > 0 && t2 > t1) {
                title = html.substring(t1 + 1, t2);
            }
        }
        if (VtbBffResponseParser.isCaptchaTitle(title)) {
            return true;
        }
        String body = html.length() > 4000 ? html.substring(0, 4000) : html;
        return VtbBffResponseParser.isCaptchaBody(body);
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
