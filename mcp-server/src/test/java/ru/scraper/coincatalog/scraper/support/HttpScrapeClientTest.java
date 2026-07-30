package ru.scraper.coincatalog.scraper.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpScrapeClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getTextReturnsBodyAndStoresCookie() {
        server.createContext("/page", exchange -> {
            exchange.getResponseHeaders().add("Set-Cookie", "sid=abc; Path=/");
            byte[] body = "<html>ok</html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        HttpScrapeClient client = new HttpScrapeClient(Duration.ofSeconds(5), 1, Duration.ofMillis(10), true);
        String body = client.getText(baseUrl + "/page");

        assertThat(body).contains("ok");
        assertThat(client.cookieManager().getCookieStore().getCookies())
                .anyMatch(c -> "sid".equals(c.getName()) && "abc".equals(c.getValue()));
    }

    @Test
    void retriesThenSucceeds() {
        AtomicInteger hits = new AtomicInteger();
        server.createContext("/flaky", exchange -> {
            if (hits.incrementAndGet() < 2) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
                return;
            }
            byte[] body = "done".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        HttpScrapeClient client = new HttpScrapeClient(Duration.ofSeconds(5), 3, Duration.ofMillis(10), true);
        assertThat(client.getText(baseUrl + "/flaky")).isEqualTo("done");
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void postJsonSendsBody() {
        server.createContext("/api", exchange -> {
            byte[] requestBody = exchange.getRequestBody().readAllBytes();
            assertThat(new String(requestBody, StandardCharsets.UTF_8)).contains("\"a\":1");
            byte[] body = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        HttpScrapeClient client = new HttpScrapeClient(Duration.ofSeconds(5), 1, Duration.ofMillis(10), true);
        String response = client.postJson(baseUrl + "/api", "{\"a\":1}", baseUrl, baseUrl + "/");
        assertThat(response).contains("\"ok\":true");
    }

    @Test
    void loadPlaywrightStorageStateAddsCookies() throws Exception {
        Path state = Files.createTempFile("storage-state", ".json");
        Files.writeString(
                state,
                """
                {"cookies":[{"name":"sess","value":"xyz","domain":"127.0.0.1","path":"/"}]}
                """);

        HttpScrapeClient client = new HttpScrapeClient(Duration.ofSeconds(5), 1, Duration.ofMillis(10), true);
        client.loadPlaywrightStorageState(state);

        assertThat(client.cookieManager().getCookieStore().getCookies())
                .anyMatch(c -> "sess".equals(c.getName()) && "xyz".equals(c.getValue()));
        Files.deleteIfExists(state);
    }

    @Test
    void failsAfterRetries() {
        server.createContext("/down", exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });

        HttpScrapeClient client = new HttpScrapeClient(Duration.ofSeconds(5), 2, Duration.ofMillis(10), true);
        assertThatThrownBy(() -> client.getText(baseUrl + "/down"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed");
    }

    @Test
    void formHeadersIncludeAjaxMarker() {
        Map<String, String> headers = HttpScrapeClient.formHeaders("https://example.com", "https://example.com/c");
        assertThat(headers.get("X-Requested-With")).isEqualTo("XMLHttpRequest");
        assertThat(headers.get("Content-Type")).contains("x-www-form-urlencoded");
    }
}
