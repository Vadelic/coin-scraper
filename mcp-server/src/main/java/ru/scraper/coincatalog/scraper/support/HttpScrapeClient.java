package ru.scraper.coincatalog.scraper.support;

import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Общий HTTP-клиент для скраперов: cookie jar, retries, UA, опционально insecure SSL.
 */
@Slf4j
public class HttpScrapeClient {

    public static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    public static final String DEFAULT_ACCEPT_LANGUAGE = "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final CookieManager cookieManager;
    private final Duration timeout;
    private final int retries;
    private final Duration baseDelay;

    public HttpScrapeClient() {
        this(Duration.ofSeconds(30), 3, Duration.ofMillis(400), false);
    }

    public HttpScrapeClient(Duration timeout, int retries, Duration baseDelay, boolean secureSsl) {
        this.timeout = Objects.requireNonNull(timeout);
        this.retries = Math.max(1, retries);
        this.baseDelay = Objects.requireNonNull(baseDelay);
        this.cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookieManager);
        if (!secureSsl) {
            builder.sslContext(insecureSslContext());
        }
        this.client = builder.build();
    }

    public static HttpScrapeClient defaults() {
        return new HttpScrapeClient();
    }

    public CookieManager cookieManager() {
        return cookieManager;
    }

    public void addCookie(String name, String value, String domain, String path) {
        HttpCookie cookie = new HttpCookie(name, value);
        cookie.setDomain(normalizeDomain(domain));
        cookie.setPath(path == null || path.isBlank() ? "/" : path);
        cookie.setVersion(0);
        String host = cookie.getDomain().startsWith(".") ? cookie.getDomain().substring(1) : cookie.getDomain();
        cookieManager.getCookieStore().add(URI.create("https://" + host + "/"), cookie);
    }

    /**
     * Загружает cookies из Playwright storage-state JSON
     * ({@code {"cookies":[{"name","value","domain","path",...}]}}).
     */
    public void loadPlaywrightStorageState(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return;
        }
        try {
            JsonNode root = MAPPER.readTree(Files.readString(path));
            JsonNode cookies = root.path("cookies");
            if (!cookies.isArray()) {
                return;
            }
            int loaded = 0;
            for (JsonNode c : cookies) {
                String name = textOrNull(c, "name");
                String value = textOrNull(c, "value");
                String domain = textOrNull(c, "domain");
                if (name == null || value == null || domain == null) {
                    continue;
                }
                addCookie(name, value, domain, textOrNull(c, "path"));
                loaded++;
            }
            log.info("Загружено cookies из {}: {}", path, loaded);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось прочитать storage-state: " + path, e);
        }
    }

    public String getText(String url) {
        return getText(url, htmlGetHeaders(null));
    }

    public String getText(String url, Map<String, String> headers) {
        return requestText("GET", url, null, headers);
    }

    public String postText(String url, byte[] body, Map<String, String> headers) {
        return requestText("POST", url, body, headers);
    }

    public String postJson(String url, String jsonBody, String origin, String referer) {
        Map<String, String> headers = jsonHeaders(origin, referer, true);
        return postText(url, jsonBody.getBytes(StandardCharsets.UTF_8), headers);
    }

    public String getJson(String url, String origin, String referer) {
        return getText(url, jsonHeaders(origin, referer, false));
    }

    public HttpResponse<byte[]> requestOnce(String method, String url, byte[] body, Map<String, String> headers)
            throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .method(
                        method,
                        body == null
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    public String requestText(String method, String url, byte[] body, Map<String, String> headers) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                HttpResponse<byte[]> response = requestOnce(method, url, body, headers);
                int code = response.statusCode();
                String text = bodyToString(response.body());
                if (code >= 400) {
                    throw new IOException("HTTP " + code + " for " + url + ": " + snippet(text));
                }
                if (text.isBlank()) {
                    throw new IOException("Empty HTTP response for " + url);
                }
                return text;
            } catch (Exception e) {
                lastError = e;
                log.warn("Attempt {}/{} {} {}: {}", attempt, retries, method, url, e.getMessage());
                if (attempt < retries) {
                    sleep(baseDelay.multipliedBy(1L << (attempt - 1)));
                }
            }
        }
        throw new IllegalStateException("Failed to " + method + " " + url + ": " + lastError);
    }

    /** Best-effort GET: логирует ошибку и не бросает. */
    public void warmUpBestEffort(String url) {
        try {
            int code = requestOnce("GET", url, null, htmlGetHeaders(null)).statusCode();
            if (code != 200) {
                log.warn("Warm-up {} returned HTTP {} (продолжаем)", url, code);
            }
        } catch (Exception e) {
            log.warn("Warm-up {} недоступен: {}", url, e.getMessage());
        }
    }

    public static Map<String, String> htmlGetHeaders(String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", DEFAULT_USER_AGENT);
        headers.put("Accept-Language", DEFAULT_ACCEPT_LANGUAGE);
        headers.put(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8");
        if (referer != null && !referer.isBlank()) {
            headers.put("Referer", referer);
        }
        return headers;
    }

    public static Map<String, String> jsonHeaders(String origin, String referer, boolean post) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", DEFAULT_USER_AGENT);
        headers.put("Accept-Language", DEFAULT_ACCEPT_LANGUAGE);
        headers.put("Accept", "application/json, text/plain, */*");
        if (origin != null && !origin.isBlank()) {
            headers.put("Origin", origin);
        }
        if (referer != null && !referer.isBlank()) {
            headers.put("Referer", referer);
        }
        if (post) {
            headers.put("Content-Type", "application/json");
        }
        return headers;
    }

    public static Map<String, String> formHeaders(String origin, String referer) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", DEFAULT_USER_AGENT);
        headers.put("Accept-Language", DEFAULT_ACCEPT_LANGUAGE);
        headers.put("Accept", "*/*");
        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
        headers.put("X-Requested-With", "XMLHttpRequest");
        if (origin != null && !origin.isBlank()) {
            headers.put("Origin", origin);
        }
        if (referer != null && !referer.isBlank()) {
            headers.put("Referer", referer);
        }
        return headers;
    }

    public static String snippet(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        return stripped.length() <= 300 ? stripped : stripped.substring(0, 300);
    }

    private static String bodyToString(byte[] body) {
        return new String(body != null ? body : new byte[0], StandardCharsets.UTF_8);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private static String normalizeDomain(String domain) {
        String d = domain.strip();
        if (d.startsWith(".")) {
            return d.substring(1);
        }
        return d;
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
