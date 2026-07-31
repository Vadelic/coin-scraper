package ru.scraper.coincatalog.scraper.zolotmd;

import lombok.experimental.UtilityClass;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.PriceParser;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class ZolotoMdPageParser {

    public static final String BASE_URL = "https://spb.zoloto-md.ru";

    private static final Map<String, String> METAL_LABELS = Map.of(
            "золото", "Золото",
            "серебро", "Серебро",
            "платина", "Платина",
            "палладий", "Палладий",
            "медно-никелевый сплав", "Медно-никелевый сплав");

    private static final Pattern PAGE_PATTERN = Pattern.compile("[?&]page=(\\d+)");
    private static final Pattern ID_PATTERN = Pattern.compile("data-id=\"([^\"]+)\"");
    private static final Pattern HREF_PATTERN =
            Pattern.compile("<a[^>]+class=\"pi-link-dark\"[^>]+href=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_PATTERN = Pattern.compile(
            "<a[^>]+class=\"pi-link-dark\"[^>]*>.*?<p>(.*?)</p>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PRICE_PATTERN =
            Pattern.compile("<span class=\"js-price\">\\s*([^<]+)\\s*</span>", Pattern.CASE_INSENSITIVE);
    private static final Pattern BUYOUT_PATTERN =
            Pattern.compile("<span class=\"js-price-buyout\">\\s*([^<]+)\\s*</span>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    public static int parseTotalPages(String html, int fallback) {
        Matcher matcher = PAGE_PATTERN.matcher(html);
        int max = fallback;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max;
    }

    public static List<ParsedCoin> parseCoins(String html) {
        List<ParsedCoin> coins = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        String[] blocks = html.split("<!-- product -->");
        for (String block : blocks) {
            if (!block.contains("js-product product-list_item")) {
                continue;
            }
            Matcher hrefMatcher = HREF_PATTERN.matcher(block);
            Matcher nameMatcher = NAME_PATTERN.matcher(block);
            if (!hrefMatcher.find() || !nameMatcher.find()) {
                continue;
            }
            String href = hrefMatcher.group(1).strip();
            String url = resolveUrl(href);
            if (!seenUrls.add(url)) {
                continue;
            }
            String name = stripTags(nameMatcher.group(1));
            if (name.isBlank()) {
                continue;
            }
            Matcher idMatcher = ID_PATTERN.matcher(block);
            Matcher priceMatcher = PRICE_PATTERN.matcher(block);
            Matcher buyoutMatcher = BUYOUT_PATTERN.matcher(block);

            String catalogNumber = idMatcher.find() ? idMatcher.group(1) : null;
            Double sellPrice = priceMatcher.find() ? PriceParser.parseRub(priceMatcher.group(1)) : null;
            Double buyPrice = buyoutMatcher.find() ? PriceParser.parseRub(buyoutMatcher.group(1)) : null;

            coins.add(new ParsedCoin(
                    new Coin(catalogNumber, name, normalizeMetal(name), parseWeightG(name), buyPrice, sellPrice),
                    url));
        }
        return coins;
    }

    public static String buildCatalogUrl(int page, int limit, String query, boolean investmentOnly) {
        StringBuilder sb = new StringBuilder(BASE_URL)
                .append("/catalog?page=")
                .append(page)
                .append("&limit=")
                .append(limit)
                .append("&available=1");
        if (investmentOnly) {
            sb.append("&country=").append(urlEncode("Россия"));
        }
        if (query != null && !query.isBlank()) {
            sb.append("&query=").append(urlEncode(query.strip()));
        }
        return sb.toString();
    }

    private static final String GRAM_UNIT = "г(?:рамм(?:а|ов)?|р)?";
    private static final Pattern WEIGHT_BEFORE_CHISTOGO =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(?:" + GRAM_UNIT + ")?\\s*чистого");
    private static final Pattern WEIGHT_AFTER_CHISTOGO = Pattern.compile(
            "чистого\\s+(?:золота|серебра|платины|палладия)\\s*[-–—:]?\\s*(\\d+(?:[.,]\\d+)?)\\s*"
                    + GRAM_UNIT);
    // Exclude year-of-issue "г.в." / "г в" without relying on \\b (broken for Cyrillic in Java).
    private static final Pattern WEIGHT_GENERIC =
            Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*" + GRAM_UNIT + "(?!\\s*\\.?\\s*в)");

    static String normalizeMetal(String name) {
        String n = name.toLowerCase();
        Matcher m = Pattern.compile("чистого\\s+(золота|серебра|платины|палладия)").matcher(n);
        if (m.find()) {
            String key = switch (m.group(1)) {
                case "золота" -> "золото";
                case "серебра" -> "серебро";
                case "платины" -> "платина";
                case "палладия" -> "палладий";
                default -> null;
            };
            return key != null ? METAL_LABELS.get(key) : null;
        }
        if (n.contains("медно-никелев")) {
            return METAL_LABELS.get("медно-никелевый сплав");
        }
        if (n.contains("золотая") || n.contains("золотой") || n.contains("золотые") || n.contains("золото")) {
            return METAL_LABELS.get("золото");
        }
        if (n.contains("серебрян") || n.contains("серебро")) {
            return METAL_LABELS.get("серебро");
        }
        if (n.contains("платинов") || n.contains("платина")) {
            return METAL_LABELS.get("платина");
        }
        if (n.contains("палладиев") || n.contains("палладий")) {
            return METAL_LABELS.get("палладий");
        }
        return null;
    }

    static Double parseWeightG(String name) {
        String n = name.toLowerCase();
        Matcher m = WEIGHT_BEFORE_CHISTOGO.matcher(n);
        if (m.find()) {
            return parseWeightValue(m.group(1));
        }
        m = WEIGHT_AFTER_CHISTOGO.matcher(n);
        if (m.find()) {
            return parseWeightValue(m.group(1));
        }
        m = WEIGHT_GENERIC.matcher(n);
        if (m.find()) {
            return parseWeightValue(m.group(1));
        }
        return null;
    }

    private static Double parseWeightValue(String raw) {
        try {
            return Double.parseDouble(raw.replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String stripTags(String text) {
        String s = TAG_PATTERN.matcher(text).replaceAll(" ");
        s = s.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return s.replaceAll("\\s+", " ").strip();
    }

    private static String resolveUrl(String href) {
        if (href.startsWith("http")) {
            return href;
        }
        return URI.create(BASE_URL).resolve(href).toString();
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public record ParsedCoin(Coin coin, String url) {}
}
