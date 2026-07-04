package ru.scraper.coincatalog.model;

import java.time.Instant;
import java.util.List;

public record ScrapeResult(
        String scrapedAt,
        ScrapeStatus scrapeStatus,
        int totalPages,
        int totalCoins,
        List<Coin> coins,
        String query,
        Boolean investmentOnly,
        String error) {

    public static ScrapeResult ok(ScrapeRequest request, int totalPages, List<Coin> coins) {
        return new ScrapeResult(
                Instant.now().toString(),
                ScrapeStatus.OK,
                totalPages,
                coins.size(),
                coins,
                request.query().orElse(null),
                request.investmentOnly().map(v -> true).orElse(null),
                null);
    }

    public static ScrapeResult captchaBlocked(ScrapeRequest request, String message) {
        return errorResult(request, ScrapeStatus.CAPTCHA_BLOCKED, message);
    }

    public static ScrapeResult error(ScrapeRequest request, String message) {
        return errorResult(request, ScrapeStatus.ERROR, message);
    }

    public static ScrapeResult notImplemented(ScrapeRequest request) {
        return error(request, "not implemented");
    }

    private static ScrapeResult errorResult(ScrapeRequest request, ScrapeStatus status, String message) {
        return new ScrapeResult(
                Instant.now().toString(),
                status,
                0,
                0,
                List.of(),
                request.query().orElse(null),
                request.investmentOnly().map(v -> true).orElse(null),
                message);
    }
}
