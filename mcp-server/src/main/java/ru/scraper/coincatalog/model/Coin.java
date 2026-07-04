package ru.scraper.coincatalog.model;

public record Coin(
        String catalogNumber,
        String name,
        String metal,
        Double weightG,
        Double buyPrice,
        Double sellPrice) {
}
