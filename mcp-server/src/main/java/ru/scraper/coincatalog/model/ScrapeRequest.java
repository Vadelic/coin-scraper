package ru.scraper.coincatalog.model;

import java.util.Optional;

/**
 * Параметры запроса на сбор каталога монет.
 */
public record ScrapeRequest(
        Optional<String> query, Optional<Boolean> investmentOnly, Optional<String> region) {

    /** Создаёт запрос; пустые строки query/region превращаются в {@link Optional#empty()}. */
    public static ScrapeRequest of(String query, Boolean investmentOnly, String region) {
        return new ScrapeRequest(
                optionalNonBlank(query),
                Optional.ofNullable(investmentOnly).filter(Boolean::booleanValue),
                optionalNonBlank(region));
    }

    private static Optional<String> optionalNonBlank(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.strip());
    }
}
