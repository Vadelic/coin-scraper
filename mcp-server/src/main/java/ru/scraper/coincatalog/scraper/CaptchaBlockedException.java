package ru.scraper.coincatalog.scraper;

public class CaptchaBlockedException extends RuntimeException {

    public CaptchaBlockedException(String message) {
        super(message);
    }
}
