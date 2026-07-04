package ru.scraper.coincatalog.model;

import java.time.Instant;
import java.util.List;

/**
 * Публичный результат сбора каталога.
 * Сериализуется в JSON по {@code coins_catalog.schema.json}.
 */
public record ScrapeResult<T>(
        String scrapedAt,
        ScrapeStatus scrapeStatus,
        int totalPages,
        int totalCoins,
        List<T> coins,
        String query,
        Boolean investmentOnly,
        String error) {

    /** Успешный сбор: элементы каталога и метаданные запроса. */
    public static <T> ScrapeResult<T> ok(ScrapeRequest request, int totalPages, List<T> coins) {
        return new ScrapeResult<>(
                Instant.now().toString(),
                ScrapeStatus.OK,
                totalPages,
                coins.size(),
                coins,
                request.query().orElse(null),
                request.investmentOnly().map(v -> true).orElse(null),
                null);
    }

    /** Источник показал CAPTCHA вместо каталога. */
    public static <T> ScrapeResult<T> captchaBlocked(ScrapeRequest request, String message) {
        return errorResult(request, ScrapeStatus.CAPTCHA_BLOCKED, message);
    }

    /** Ошибка загрузки или парсинга. */
    public static <T> ScrapeResult<T> error(ScrapeRequest request, String message) {
        return errorResult(request, ScrapeStatus.ERROR, message);
    }

    /** Источник не реализован в реестре. */
    public static <T> ScrapeResult<T> notImplemented(ScrapeRequest request) {
        return error(request, "not implemented");
    }

    private static <T> ScrapeResult<T> errorResult(ScrapeRequest request, ScrapeStatus status, String message) {
        return new ScrapeResult<>(
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
