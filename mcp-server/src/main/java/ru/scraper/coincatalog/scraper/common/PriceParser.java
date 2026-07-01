package ru.scraper.coincatalog.scraper.common;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PriceParser {

    private static final Pattern PRICE_PATTERN = Pattern.compile("([\\d\\s]+(?:[.,]\\d+)?)");

    private PriceParser() {}

    /**
     * Parses price from Russian storefront strings like "99 700 ₽" or "99 700,50".
     */
    public static Double parseRub(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = PRICE_PATTERN.matcher(raw);
        if (!matcher.find()) {
            return null;
        }
        String normalized = matcher.group(1).replace(" ", "").replace(",", ".");
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
