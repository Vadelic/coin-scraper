package ru.scraper.coincatalog.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Coin(
        @JsonProperty("catalog_number") String catalogNumber,
        String name,
        String metal,
        @JsonProperty("weight_g") Double weightG,
        @JsonProperty("buy_price") Double buyPrice,
        @JsonProperty("sell_price") Double sellPrice) {
}
