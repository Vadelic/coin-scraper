package ru.scraper.coincatalog.model;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Монета каталога: каталожный номер, название, металл, вес и цены выкупа/продажи.
 *
 * <p>{@link Schema#nullable()} нужен для MCP {@code generateOutputSchema}: без него Spring AI
 * генерирует {@code type: number/string} без {@code null}, и ответы с отсутствующими ценами
 * не проходят output validation.
 */
public record Coin(
        @Schema(nullable = true) String catalogNumber,
        String name,
        @Schema(nullable = true) String metal,
        @Schema(nullable = true) Double weightG,
        @Schema(nullable = true) Double buyPrice,
        @Schema(nullable = true) Double sellPrice) {}
