package ru.scraper.coincatalog.model;

public enum ScrapeStatus {
    OK("ok"),
    CAPTCHA_BLOCKED("captcha_blocked"),
    ERROR("error");

    private final String value;

    ScrapeStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
