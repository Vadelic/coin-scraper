package ru.scraper.coincatalog.model;

/** Источник вернул CAPTCHA вместо каталога монет. */
public class CaptchaBlockedException extends RuntimeException {

    public CaptchaBlockedException(String message) {
        super(message);
    }
}
