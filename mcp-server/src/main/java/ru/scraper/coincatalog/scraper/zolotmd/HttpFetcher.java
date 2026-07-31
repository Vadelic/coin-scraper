package ru.scraper.coincatalog.scraper.zolotmd;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.scraper.support.HttpScrapeClient;

/** Thin wrapper around {@link HttpScrapeClient} for ZolotoMd (keeps existing injection point). */
@Service
@RequiredArgsConstructor
public class HttpFetcher {

    private final HttpScrapeClient httpScrapeClient;

    public String fetchText(String url) {
        return httpScrapeClient.getText(url);
    }
}
