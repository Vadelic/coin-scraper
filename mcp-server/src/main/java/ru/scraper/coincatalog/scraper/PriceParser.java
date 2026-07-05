package ru.scraper.coincatalog.scraper;

import lombok.experimental.UtilityClass;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class PriceParser {

    private static final Pattern PRICE_TOKEN = Pattern.compile("\\d[\\d\\s]*(?:[.,]\\d+)?");

    /**
     * Parses price from Russian storefront strings like "99 700 ₽" or "99 700,50".
     */
    public static Double parseRub(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = PRICE_TOKEN.matcher(normalizeSpaces(raw));
        if (!matcher.find()) {
            return null;
        }
        return parseNumberToken(matcher.group());
    }

    /** Last monetary amount in the string — актуальная цена при нескольких числах (старая + новая). */
    public static Double parseLastRub(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher matcher = PRICE_TOKEN.matcher(normalizeSpaces(raw));
        Double last = null;
        while (matcher.find()) {
            Double parsed = parseNumberToken(matcher.group());
            if (parsed != null) {
                last = parsed;
            }
        }
        return last;
    }

    private static String normalizeSpaces(String raw) {
        return raw.replace('\u00a0', ' ').replace('\u202f', ' ').strip();
    }

    private static Double parseNumberToken(String token) {
        String normalized = token.replace(" ", "").replace(",", ".");
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
