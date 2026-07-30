package ru.scraper.coincatalog.scraper.rshb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.support.HttpScrapeClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Скрапер каталога РСХБ через HTTP (SSR HTML + Elasticsearch buyout API). */
@Slf4j
@Service("RSHB")
public class RshbScraper implements CoinScraper<Coin> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int BUYOUT_SKU_BATCH_SIZE = 50;

    private final HttpScrapeClient http;
    private final int retries;
    private final double pageDelaySeconds;
    private final int pageSize;
    private final Function<PageLoadRequest, String> pageHtmlOverride;

    public RshbScraper() {
        this(HttpScrapeClient.defaults(), null);
    }

    RshbScraper(Function<PageLoadRequest, String> pageHtmlOverride) {
        this(HttpScrapeClient.defaults(), pageHtmlOverride);
    }

    RshbScraper(HttpScrapeClient http, Function<PageLoadRequest, String> pageHtmlOverride) {
        this(http, 3, 0.4, RshbPageParser.DEFAULT_PAGE_SIZE, pageHtmlOverride);
    }

    RshbScraper(
            HttpScrapeClient http,
            int retries,
            double pageDelaySeconds,
            int pageSize,
            Function<PageLoadRequest, String> pageHtmlOverride) {
        this.http = http;
        this.retries = Math.max(1, retries);
        this.pageDelaySeconds = Math.max(0, pageDelaySeconds);
        this.pageSize = pageSize > 0 ? pageSize : RshbPageParser.DEFAULT_PAGE_SIZE;
        this.pageHtmlOverride = pageHtmlOverride;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";
        String regionCode = resolveRegionCode(region, investmentOnly);
        http.addCookie(
                RshbPageParser.REGION_COOKIE_NAME,
                regionCode,
                "coins.rshb.ru",
                "/");
        log.info("Регион каталога: {}={}", RshbPageParser.REGION_COOKIE_NAME, regionCode);

        List<Coin> allCoins = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        int processed = 0;
        Integer lastPage = null;
        int pageNum = 1;

        while (true) {
            if (processed > 0 && pageDelaySeconds > 0) {
                sleep(pageDelaySeconds);
            }

            log.info("Загружаю страницу {}{}", pageNum, lastPage != null ? "/" + lastPage : "");
            String html = loadPageHtml(pageNum, normalizedQuery, investmentOnly);
            if (html == null || html.isBlank()) {
                log.error("Страница {} не загрузилась — пропуск", pageNum);
                if (processed == 0) {
                    break;
                }
                pageNum++;
                if (lastPage != null && pageNum > lastPage) {
                    break;
                }
                continue;
            }

            if (lastPage == null) {
                lastPage = RshbPageParser.parsePaginationMax(RshbPageParser.parsePaginationHrefs(html));
                log.info("Всего страниц: {} (page_size={})", lastPage, pageSize);
            }

            List<RshbPageParser.ParsedCard> pageCards = RshbPageParser.parseCardsFromHtml(html);
            enrichBuyPrices(pageCards);

            List<Coin> newCoins = new ArrayList<>();
            for (RshbPageParser.ParsedCard parsed : pageCards) {
                String key = parsed.url();
                if (!seenUrls.add(key)) {
                    log.warn(
                            "Дубликат карточки (url {}): «{}», артикул={}",
                            key,
                            parsed.coin().name(),
                            parsed.coin().catalogNumber());
                    continue;
                }
                if (parsed.coin().sellPrice() == null) {
                    log.warn(
                            "Нет sell_price для «{}» (артикул {})",
                            parsed.coin().name(),
                            parsed.coin().catalogNumber());
                }
                newCoins.add(parsed.coin());
            }
            allCoins.addAll(newCoins);
            processed++;

            log.info(
                    "Страница {}/{}: {} монет (новых: {}, всего: {})",
                    pageNum,
                    lastPage,
                    pageCards.size(),
                    newCoins.size(),
                    allCoins.size());

            if (pageCards.isEmpty()) {
                log.info("Страница {} пустая — остановка", pageNum);
                break;
            }
            if (pageNum >= lastPage) {
                break;
            }
            pageNum++;
        }

        return new ScrapePayload<>(processed, allCoins);
    }

    private String loadPageHtml(int pageNum, String searchText, boolean investmentOnly) {
        PageLoadRequest request = new PageLoadRequest(pageNum, searchText, investmentOnly);
        if (pageHtmlOverride != null) {
            return pageHtmlOverride.apply(request);
        }

        String url = RshbPageParser.buildUrl(pageNum, pageSize, searchText, investmentOnly);
        if (investmentOnly) {
            log.info("Фильтр: только инвестиционные монеты (subjects={})", RshbPageParser.INVESTMENT_SUBJECTS);
        }
        if (!searchText.isBlank()) {
            log.info("Поиск search_text=«{}»", searchText);
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                return http.getText(url, HttpScrapeClient.htmlGetHeaders(RshbPageParser.BASE_URL + "/"));
            } catch (Exception e) {
                lastError = e;
                log.warn("Страница {}, попытка {}/{} — ошибка: {}", pageNum, attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(Math.pow(2, attempt));
                }
            }
        }
        log.error("Страница {}: все попытки исчерпаны: {}", pageNum, lastError != null ? lastError.getMessage() : "");
        return null;
    }

    private void enrichBuyPrices(List<RshbPageParser.ParsedCard> cards) {
        List<String> skus = new ArrayList<>();
        for (RshbPageParser.ParsedCard card : cards) {
            Coin coin = card.coin();
            if (coin.catalogNumber() != null && coin.buyPrice() == null) {
                skus.add(coin.catalogNumber());
            }
        }
        if (skus.isEmpty()) {
            return;
        }
        Map<String, Double> buyoutBySku = fetchBuyoutPrices(skus);
        if (buyoutBySku.isEmpty()) {
            return;
        }

        for (int i = 0; i < cards.size(); i++) {
            RshbPageParser.ParsedCard card = cards.get(i);
            Coin coin = card.coin();
            if (coin.buyPrice() != null || coin.catalogNumber() == null) {
                continue;
            }
            Double buyPrice = buyoutBySku.get(coin.catalogNumber());
            if (buyPrice != null) {
                cards.set(
                        i,
                        new RshbPageParser.ParsedCard(
                                new Coin(
                                        coin.catalogNumber(),
                                        coin.name(),
                                        coin.metal(),
                                        coin.weightG(),
                                        buyPrice,
                                        coin.sellPrice()),
                                card.url()));
            }
        }
    }

    private Map<String, Double> fetchBuyoutPrices(List<String> skus) {
        List<String> unique = skus.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, Double> registry = new HashMap<>();
        if (unique.isEmpty()) {
            return registry;
        }

        for (int offset = 0; offset < unique.size(); offset += BUYOUT_SKU_BATCH_SIZE) {
            List<String> batch = unique.subList(offset, Math.min(offset + BUYOUT_SKU_BATCH_SIZE, unique.size()));
            Map<String, Object> payload = Map.of(
                    "query", Map.of("terms", Map.of("sku", batch)),
                    "size", batch.size());
            try {
                String body = MAPPER.writeValueAsString(payload);
                String response = http.postJson(
                        RshbPageParser.PRODUCT_SEARCH_URL,
                        body,
                        RshbPageParser.BASE_URL,
                        RshbPageParser.BASE_URL + "/");
                JsonNode data = MAPPER.readTree(response);
                for (JsonNode hit : data.path("hits").path("hits")) {
                    JsonNode source = hit.get("_source");
                    if (source == null || !source.isObject()) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sourceMap = MAPPER.convertValue(source, Map.class);
                    String sku = source.path("sku").asText(null);
                    Double buyout = RshbPageParser.buyoutPriceFromProductSource(sourceMap);
                    if (sku != null && buyout != null) {
                        registry.put(sku, buyout);
                    }
                }
            } catch (Exception e) {
                log.warn("product/_search (buyout): {}", e.getMessage());
            }
        }

        if (!registry.isEmpty()) {
            log.info("buy_price из product/_search: {} sku", registry.size());
        }
        return registry;
    }

    private static String resolveRegionCode(String region, boolean investmentOnly) {
        if (region != null && !region.isBlank()) {
            return region.strip();
        }
        return investmentOnly
                ? RshbPageParser.DEFAULT_REGION_CODE
                : RshbPageParser.IN_STOCK_CATALOG_REGION_CODE;
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }

    public record PageLoadRequest(int pageNum, String searchText, boolean investmentOnly) {}
}
