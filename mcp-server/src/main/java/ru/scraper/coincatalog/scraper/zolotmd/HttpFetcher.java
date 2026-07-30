package ru.scraper.coincatalog.scraper.zolotmd;

import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.scraper.support.HttpScrapeClient;

/** Thin wrapper around {@link HttpScrapeClient} for ZolotoMd (keeps existing injection point). */
@Service
public class HttpFetcher {

    private final HttpScrapeClient client;

    public HttpFetcher() {
        this(HttpScrapeClient.defaults());
    }

    public HttpFetcher(HttpScrapeClient client) {
        this.client = client;
    }

    public static HttpFetcher defaults() {
        return new HttpFetcher(HttpScrapeClient.defaults());
    }

    public String fetchText(String url) {
        return client.getText(url);
    }
}
