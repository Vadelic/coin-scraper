package ru.scraper.coincatalog.scraper.zolotmd;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Objects;

@Service
@Slf4j
public class HttpFetcher {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient client;
    private final Duration timeout;
    private final int retries;
    private final Duration baseDelay;

    public HttpFetcher() {
        this(Duration.ofSeconds(30), 3, Duration.ofMillis(400), false);
    }

    public HttpFetcher(Duration timeout, int retries, Duration baseDelay, boolean secureSsl) {
        this.timeout = Objects.requireNonNull(timeout);
        this.retries = Math.max(1, retries);
        this.baseDelay = Objects.requireNonNull(baseDelay);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .version(HttpClient.Version.HTTP_1_1)
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
        if (!secureSsl) {
            builder.sslContext(insecureSslContext());
        }
        this.client = builder.build();
    }

    public static HttpFetcher defaults() {
        return new HttpFetcher(Duration.ofSeconds(30), 3, Duration.ofMillis(400), false);
    }

    public String fetchText(String url) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(timeout)
                        .header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                        .GET()
                        .build();
                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() >= 400) {
                    throw new IOException("HTTP " + response.statusCode() + " for " + url);
                }
                String body = response.body();
                if (body == null || body.isBlank()) {
                    throw new IOException("Empty HTTP response for " + url);
                }
                return body;
            } catch (Exception e) {
                lastError = e;
                log.warn("Attempt {}/{} for {}: {}", attempt, retries, url, e.getMessage());
                if (attempt < retries) {
                    sleep(baseDelay.multipliedBy(1L << (attempt - 1)));
                }
            }
        }
        throw new IllegalStateException("Failed to load " + url + ": " + lastError);
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
