package ru.scraper.coincatalog.scraper.rshb;

import lombok.experimental.UtilityClass;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.PriceParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class RshbPageParser {

    public static final String BASE_URL = "https://coins.rshb.ru";
    public static final String PRODUCT_SEARCH_URL =
            BASE_URL + "/api/catalog/vue_storefront_magento_1_product/product/_search";
    public static final String INVESTMENT_SUBJECTS = "5506";
    public static final String DEFAULT_REGION_CODE = "77";
    /** Регион по умолчанию для общего каталога с in_stock=true (национальная витрина). */
    public static final String IN_STOCK_CATALOG_REGION_CODE = "0";
    public static final String REGION_COOKIE_NAME = "x-region";
    public static final int DEFAULT_PAGE_SIZE = 99;

    public static final String CARD_LINK_SELECTOR = "a[href^='/p/']";
    public static final String PAGINATION_LINK_SELECTOR = "a[href*='page=']";

    private static final Pattern METAL_LINE_RE = Pattern.compile(
            "^(золото|серебро|платина|палладий)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SELL_PRICE_ONLY_RE = Pattern.compile(
            "^\\d[\\d\\s.,]*\\s*(?:₽|руб\\.?)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NOMINAL_VALUE_RE = Pattern.compile(
            "^\\d+(?:[.,]\\d+)?\\s*(?:RUB|RUR|руб\\.?)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WEIGHT_ONLY_RE = Pattern.compile(
            "^\\d+(?:[.,]\\d+)?\\s*г(?:р|рамм)?\\.?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WEIGHT_RE = Pattern.compile(
            "(\\d+(?:[,.]\\d+)?)\\s*г(?:р|рамм)?",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PAGE_RE = Pattern.compile("[?&]page=(\\d+)");

    private static final List<String> STOCK_STATUS_MARKERS = List.of(
            "нет в наличии", "предзаказ", "нет в продаже", "распродано");
    private static final List<String> ATTRIBUTE_LABELS = List.of(
            "Номинал", "Металл", "Проба", "Чистого металла", "Тираж", "Выкуп", "Цена выкупа");
    private static final List<String> BUYOUT_LABELS = List.of("Выкуп", "Цена выкупа");
    private static final List<String> NAME_ATTR_CUT_LABELS = List.of(
            "Номинал", "Металл", "Проба", "Чистого металла", "Тираж", "Выкуп");

    public record CardInput(String rawText, String href, String linkText, String priceBoxText) {}

    public record ParsedCard(Coin coin, String url) {}

    public static String buildUrl(int page, int pageSize, String searchText, boolean investmentOnly) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("page", String.valueOf(page));
        params.put("page_size", String.valueOf(pageSize));
        params.put("in_stock", "true");
        String query = searchText != null ? searchText.strip() : "";
        if (!query.isEmpty()) {
            params.put("search_text", query);
        }
        if (investmentOnly) {
            params.put("subjects", INVESTMENT_SUBJECTS);
        }
        return BASE_URL + "/?" + encodeParams(params);
    }

    public static int parsePaginationMax(Iterable<String> hrefs) {
        int maxPage = 1;
        for (String href : hrefs) {
            if (href == null || href.isBlank()) {
                continue;
            }
            Matcher matcher = PAGE_RE.matcher(href);
            if (matcher.find()) {
                maxPage = Math.max(maxPage, Integer.parseInt(matcher.group(1)));
            }
        }
        return maxPage;
    }

    public static String parseSkuFromProductHref(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        String trimmed = href.strip();
        if (!trimmed.startsWith("/p/")) {
            return null;
        }
        String rest = trimmed.substring("/p/".length());
        int slash = rest.indexOf('/');
        String segment = slash >= 0 ? rest.substring(0, slash) : rest;
        if (segment.isBlank()) {
            return null;
        }
        String decoded = java.net.URLDecoder.decode(segment, StandardCharsets.UTF_8).strip();
        return decoded.isEmpty() ? null : decoded;
    }

    public static Optional<ParsedCard> parseCard(CardInput input) {
        List<String> lines = nonEmptyLines(input.rawText());
        if (lines.isEmpty()) {
            return Optional.empty();
        }

        String href = input.href() != null ? input.href() : "";
        String url = BASE_URL + href;
        String sku = parseSkuFromProductHref(href);

        Double sellPrice = sellPriceFromPriceBox(input.priceBoxText());
        if (sellPrice == null) {
            sellPrice = sellPriceFromCardText(input.rawText());
        }

        String nameSource = input.linkText() != null && !input.linkText().isBlank()
                ? input.linkText().strip()
                : input.rawText();
        String name = extractNameFromListing(nameSource);
        if (name.isBlank()) {
            name = href.replaceAll("/$", "");
            int lastSlash = name.lastIndexOf('/');
            name = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
        }

        String weightRaw = extractLabeledValue(input.rawText(), "Чистого металла");
        Double weightG = weightRaw != null ? parseWeight(weightRaw) : null;
        String metalRaw = extractLabeledValue(input.rawText(), "Металл");
        Double buyPrice = buyoutPriceFromCardText(input.rawText());

        Coin coin = new Coin(sku, name, normalizeMetal(metalRaw), weightG, buyPrice, sellPrice);
        return Optional.of(new ParsedCard(coin, url));
    }

    public static Double sellPriceFromPriceBox(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Double price = PriceParser.parseRub(text.strip());
        return price != null && price > 10 ? price : null;
    }

    public static Double sellPriceFromCardText(String rawText) {
        for (String line : nonEmptyLines(rawText)) {
            if (isStockStatusLine(line)) {
                continue;
            }
            if (!line.contains("₽") && !SELL_PRICE_ONLY_RE.matcher(line).matches()) {
                continue;
            }
            Double price = PriceParser.parseRub(line);
            if (price != null && price > 10) {
                return price;
            }
        }
        return null;
    }

    public static Double buyoutPriceFromCardText(String rawText) {
        for (String label : BUYOUT_LABELS) {
            String value = extractLabeledValue(rawText, label);
            if (value != null) {
                Double price = PriceParser.parseRub(value);
                if (price != null) {
                    return price;
                }
            }
        }
        return null;
    }

    public static Double buyoutPriceFromProductSource(Map<String, Object> source) {
        if (source == null) {
            return null;
        }
        Object canBeBuyouted = source.get("can_be_buyouted");
        int flag = canBeBuyouted instanceof Number n ? n.intValue() : 0;
        if (flag != 1) {
            return null;
        }
        Object raw = source.get("buyout_price");
        if (raw == null || "".equals(raw)) {
            return null;
        }
        try {
            double price = raw instanceof Number n ? n.doubleValue() : Double.parseDouble(raw.toString());
            return price > 0 ? price : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String extractNameFromListing(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cut = text;
        for (String label : NAME_ATTR_CUT_LABELS) {
            int idx = cut.indexOf(label);
            if (idx > 0) {
                cut = cut.substring(0, idx);
            }
        }
        List<String> lines = nonEmptyLines(cut);
        if (lines.isEmpty() && !cut.isBlank()) {
            lines = List.of(stripStatusPrefix(cut.strip()));
        }
        List<String> candidates = new ArrayList<>();
        for (String line : lines) {
            String stripped = stripStatusPrefix(line);
            if (!stripped.isBlank() && isNameCandidateLine(stripped)) {
                candidates.add(stripped);
            }
        }
        if (candidates.isEmpty()) {
            return "";
        }
        return candidates.stream().max((a, b) -> Integer.compare(nameLineScore(a), nameLineScore(b))).orElse("");
    }

    public static String normalizeMetal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = METAL_LINE_RE.matcher(text.strip());
        if (matcher.find()) {
            String name = matcher.group(1);
            return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1).toLowerCase(Locale.ROOT);
        }
        return text.strip();
    }

    public static Double parseWeight(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = WEIGHT_RE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return Double.parseDouble(matcher.group(1).replace(',', '.'));
    }

    public static String cardTextJs() {
        return """
                el => {
                  const wrapper = el.closest('.product-wrapper');
                  const root = wrapper || el.closest(
                    'article, li, [class*="product"], [class*="card"], [class*="item"]'
                  ) || el.parentElement;
                  const priceEl = (wrapper || root || el).querySelector('.price-box');
                  return {
                    card: (root || el).innerText || el.innerText || '',
                    link: el.innerText || '',
                    priceBox: (priceEl && priceEl.innerText) ? priceEl.innerText.trim() : '',
                  };
                }
                """;
    }

    private static String extractLabeledValue(String text, String label) {
        String target = normalizeLabel(label);
        String[] lines = text.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (normalizeLabel(lines[i]).equals(target) && i + 1 < lines.length) {
                String value = lines[i + 1].strip();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private static List<String> nonEmptyLines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null) {
            return lines;
        }
        for (String line : text.split("\\R")) {
            String stripped = line.strip();
            if (!stripped.isEmpty()) {
                lines.add(stripped);
            }
        }
        return lines;
    }

    private static boolean isStockStatusLine(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        return STOCK_STATUS_MARKERS.stream().anyMatch(low::contains);
    }

    private static boolean looksLikeSellPriceLine(String line) {
        String stripped = line.strip();
        if (stripped.contains("₽")) {
            if (SELL_PRICE_ONLY_RE.matcher(stripped).matches()) {
                return true;
            }
            return stripped.startsWith("₽") || stripped.endsWith("₽") && stripped.length() < 30;
        }
        return SELL_PRICE_ONLY_RE.matcher(stripped).matches();
    }

    private static String stripStatusPrefix(String line) {
        String low = line.toLowerCase(Locale.ROOT);
        for (String marker : STOCK_STATUS_MARKERS) {
            if (low.startsWith(marker)) {
                return line.substring(marker.length()).replaceAll("^[\\s,—–-]+", "").replaceAll("[\\s,—–-]+$", "");
            }
        }
        return line.strip();
    }

    private static boolean isNameCandidateLine(String line) {
        if (isStockStatusLine(line) || looksLikeSellPriceLine(line)) {
            return false;
        }
        if (NOMINAL_VALUE_RE.matcher(line).matches() || WEIGHT_ONLY_RE.matcher(line).matches()) {
            return false;
        }
        if (ATTRIBUTE_LABELS.contains(line) || line.length() <= 5) {
            return false;
        }
        if (line.replace(" ", "").chars().allMatch(Character::isDigit)) {
            return false;
        }
        return !(METAL_LINE_RE.matcher(line).find() && line.split("\\s+").length <= 2);
    }

    private static int nameLineScore(String line) {
        if (!isNameCandidateLine(line)) {
            return -1;
        }
        if (Pattern.compile("[а-яА-ЯёЁa-zA-Z]{4,}").matcher(line).find()) {
            return 10 + Math.min(line.length(), 80);
        }
        return line.length();
    }

    private static String normalizeLabel(String value) {
        return value.strip().replaceAll(":$", "").toLowerCase(Locale.ROOT);
    }

    private static String encodeParams(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
