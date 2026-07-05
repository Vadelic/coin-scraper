package ru.scraper.coincatalog.scraper.sberbank;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Скрапер каталога Сбербанка через HTTP API. */
@Slf4j
@Service("SBERBANK")
public class SberbankScraper implements CoinScraper<Coin> {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";
    private static final Logger log = LoggerFactory.getLogger(SberbankScraper.class);

    private final HttpClient client;
    private final ObjectMapper objectMapper;
    private final Duration requestTimeout;
    private final int retries;

    public SberbankScraper() {
        this(Duration.ofSeconds(15), Duration.ofSeconds(60), 3, false);
    }

    SberbankScraper(Duration connectTimeout, Duration requestTimeout, int retries, boolean secureSsl) {
        this.requestTimeout = requestTimeout;
        this.retries = Math.max(1, retries);
        this.objectMapper = new ObjectMapper();
        CookieManager cookieManager = new CookieManager();
        cookieManager.setCookiePolicy(CookiePolicy.ACCEPT_ALL);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(cookieManager);
        if (!secureSsl) {
            builder.sslContext(insecureSslContext());
        }
        this.client = builder.build();
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";
        List<String> sections = SberbankResponseParser.resolveSections(investmentOnly);

        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                openVitrineBestEffort(attempt);

                List<ObjectNode> entities;
                int pagesProcessed;
                if (investmentOnly) {
                    log.info("Фильтр sections={}", sections);
                }

                List<SberbankResponseParser.MetalBatch> perMetal = new ArrayList<>();
                for (String metal : SberbankResponseParser.DEFAULT_METAL_FILTERS) {
                    log.info("POST metals=[{}]", metal);
                    ObjectNode payload = SberbankResponseParser.buildPayload(
                            0,
                            SberbankResponseParser.DEFAULT_PAGE_SIZE,
                            SberbankResponseParser.DEFAULT_CITY,
                            SberbankResponseParser.DEFAULT_CONDITION,
                            "",
                            List.of(metal),
                            sections,
                            List.of());
                    JsonNode data = postCatalog(payload);
                    List<ObjectNode> rows = SberbankResponseParser.entitiesFromCatalogResponse(data);
                    log.info("Металл «{}»: строк в ответе {}", metal, rows.size());
                    perMetal.add(new SberbankResponseParser.MetalBatch(metal, rows));
                }
                var mergeResult = SberbankResponseParser.mergeEntitiesWithMetalFilters(perMetal);
                entities = new ArrayList<>(mergeResult.entities());
                pagesProcessed = SberbankResponseParser.DEFAULT_METAL_FILTERS.size();
                log.info(
                        "После объединения по id: уникальных {} (всего строк из всех POST: {})",
                        entities.size(),
                        mergeResult.rawRowCount());

                fetchAndMergeBuyout(entities, "");
                return new ScrapePayload(pagesProcessed, toCoins(entities, normalizedQuery));
            } catch (Exception e) {
                lastError = e;
                log.warn("Попытка {}/{} — ошибка: {}", attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(requestTimeout.dividedBy(30).multipliedBy(1L << attempt));
                }
            }
        }
        throw new IllegalStateException("Не удалось загрузить каталог: " + lastError);
    }

    List<Coin> toCoins(List<ObjectNode> entities, String query) {
        List<Coin> coins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ObjectNode entity : entities) {
            var coinOpt = SberbankResponseParser.entityToCoin(entity);
            if (coinOpt.isEmpty()) {
                log.warn("Пропуск записи: нет id");
                continue;
            }
            Coin coin = coinOpt.get();
            if (coin.name() == null || coin.name().isBlank()) {
                log.warn("Пропуск записи: пустое name, catalog_number={}", coin.catalogNumber());
                continue;
            }
            if (!SberbankResponseParser.coinMatchesQuery(coin, query)) {
                continue;
            }
            String key = entity.hasNonNull("id") ? entity.get("id").asText() : SberbankResponseParser.dedupeKey(coin);
            if (!seen.add(key)) {
                log.warn(
                        "Дубликат (id {}): «{}», артикул={}",
                        key,
                        coin.name(),
                        coin.catalogNumber());
                continue;
            }
            if (coin.sellPrice() == null) {
                log.warn(
                        "Нет sell_price для «{}» (артикул {})",
                        coin.name(),
                        coin.catalogNumber());
            }
            coins.add(coin);
        }
        return coins;
    }

    private void fetchAndMergeBuyout(List<ObjectNode> entities, String query) {
        String buyoutPath = SberbankResponseParser.buildBuyoutPath(
                0, SberbankResponseParser.DEFAULT_PAGE_SIZE, query);
        String buyoutUrl = SberbankResponseParser.absoluteUrl(buyoutPath);
        try {
            log.info("Запрос GET {}", buyoutUrl);
            JsonNode buyoutData = parseJsonBody(request(buyoutUrl, "GET", apiJsonGetHeaders(), null));
            List<JsonNode> buyoutList = SberbankResponseParser.entitiesFromBuyoutResponse(buyoutData);
            log.info("Выкуп: записей {}", buyoutList.size());
            SberbankResponseParser.mergeBuyoutIntoCatalog(entities, buyoutList);
        } catch (Exception e) {
            log.warn("Выкуп (GET buyout) недоступен, цены выкупа только из каталога: {}", e.getMessage());
        }
    }

    private void openVitrineBestEffort(int attempt) {
        try {
            log.info(
                    "Открываю витрину {} (попытка {}/{})",
                    SberbankResponseParser.CATALOG_URL,
                    attempt,
                    retries);
            int vitrineCode = request(
                            SberbankResponseParser.CATALOG_URL,
                            "GET",
                            vitrineGetHeaders(),
                            null)
                    .statusCode();
            if (vitrineCode != 200) {
                log.warn("Витрина вернула HTTP {} (ожидали cookie-сессию; продолжаем)", vitrineCode);
            }
        } catch (Exception e) {
            log.warn("Витрина недоступна (продолжаем без cookie-сессии): {}", e.getMessage());
        }
    }

    private JsonNode postCatalog(ObjectNode payload) throws Exception {
        String catalogUrl = SberbankResponseParser.absoluteUrl(SberbankResponseParser.API_PATH);
        byte[] body = objectMapper.writeValueAsBytes(payload);
        HttpResponse<byte[]> response = request(catalogUrl, "POST", apiJsonPostHeaders(), body);
        return parseJsonBody(response);
    }

    private HttpResponse<byte[]> request(String url, String method, String[] headers, byte[] body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestTimeout)
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode parseJsonBody(HttpResponse<byte[]> response) {
        int code = response.statusCode();
        byte[] raw = response.body();
        String text = new String(raw != null ? raw : new byte[0], java.nio.charset.StandardCharsets.UTF_8).strip();
        if (code != 200) {
            throw new IllegalStateException("HTTP " + code + ": " + snippet(text));
        }
        String low = text.toLowerCase();
        if (!low.startsWith("{") && !low.startsWith("[")) {
            throw new IllegalStateException("Не JSON (content): " + snippet(text));
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("JSON ошибка: " + e.getMessage() + "; начало: " + snippet(text), e);
        }
    }

    private static String snippet(String text) {
        if (text.length() <= 300) {
            return text;
        }
        return text.substring(0, 300);
    }

    private static String[] vitrineGetHeaders() {
        return new String[] {
            "User-Agent", USER_AGENT,
            "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer", SberbankResponseParser.ORIGIN + "/"
        };
    }

    private static String[] apiJsonPostHeaders() {
        return new String[] {
            "User-Agent", USER_AGENT,
            "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin", SberbankResponseParser.ORIGIN,
            "Referer", SberbankResponseParser.CATALOG_URL,
            "Content-Type", "application/json",
            "Accept", "application/json"
        };
    }

    private static String[] apiJsonGetHeaders() {
        return new String[] {
            "User-Agent", USER_AGENT,
            "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Origin", SberbankResponseParser.ORIGIN,
            "Referer", SberbankResponseParser.CATALOG_URL,
            "Accept", "application/json"
        };
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during retry delay", e);
        }
    }

    private static SSLContext insecureSslContext() {
        try {
            TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new SecureRandom());
            return context;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create insecure SSL context", e);
        }
    }
}
