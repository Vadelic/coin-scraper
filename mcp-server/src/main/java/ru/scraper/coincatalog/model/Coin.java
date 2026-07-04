package ru.scraper.coincatalog.model;

/**
 * Монета каталога: каталожный номер, название, металл, вес и цены выкупа/продажи.
 */
public record Coin(
        String catalogNumber,
        String name,
        String metal,
        Double weightG,
        Double buyPrice,
        Double sellPrice) {}
