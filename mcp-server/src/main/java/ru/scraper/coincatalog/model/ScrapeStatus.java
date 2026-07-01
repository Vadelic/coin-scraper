package ru.scraper.coincatalog.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum ScrapeStatus {
    OK("ok"),
    CAPTCHA_BLOCKED("captcha_blocked"),
    ERROR("error");

    private final String value;

    ScrapeStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
