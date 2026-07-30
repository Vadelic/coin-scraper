package ru.scraper.coincatalog.scraper.goldenplata;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.PriceParser;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class GoldenplataPageParser {

    public static final String BASE_URL = "https://goldenplata.ru";
    /** Корневой /catalog/ — только категории; листинг монет без браузера — секция investment. */
    public static final String CATALOG_URL = BASE_URL + "/catalog/investitsionnye-monety/";
    public static final String INVESTMENT_CATALOG_URL =
            BASE_URL + "/catalog/investitsionnye-monety/rossiyskiye/";

    private static final Pattern PAGEN_RE = Pattern.compile("[?&]PAGEN_4=(\\d+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern ANALYTICS_RE = Pattern.compile(
            "<script type=\"application/json\" class=\"js-analytics-payload\">\\s*(.*?)\\s*</script>",
            Pattern.DOTALL);

    private static final Pattern WEIGHT_RE = Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*гр\\.?", Pattern.CASE_INSENSITIVE);

    private static final Map<String, String> METAL_MAP = Map.of(
            "золото", "Золото",
            "серебро", "Серебро",
            "платина", "Платина",
            "палладий", "Палладий");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String resolveCatalogBase(boolean investmentOnly) {
        return investmentOnly ? INVESTMENT_CATALOG_URL : CATALOG_URL;
    }

    public static String buildCatalogUrl(String baseUrl, int page, String query) {
        List<String> params = new ArrayList<>();
        if (query != null && !query.isBlank()) {
            params.add("q=" + urlEncode(query.strip()));
        }
        if (page > 1) {
            params.add("PAGEN_4=" + page);
        }
        if (params.isEmpty()) {
            return baseUrl;
        }
        String sep = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + sep + String.join("&", params);
    }

    public static int parseTotalPages(String html) {
        Matcher matcher = PAGEN_RE.matcher(html);
        int max = 1;
        while (matcher.find()) {
            max = Math.max(max, Integer.parseInt(matcher.group(1)));
        }
        return max;
    }

    public static boolean hasAnalyticsPayload(String html) {
        return ANALYTICS_RE.matcher(html).find();
    }

    public static List<Coin> parseCoinsFromHtml(String html) {
        List<Coin> coins = new ArrayList<>();
        Matcher matcher = ANALYTICS_RE.matcher(html);
        while (matcher.find()) {
            String rawJson = matcher.group(1);
            try {
                JsonNode data = MAPPER.readTree(rawJson);
                if (!data.isObject()) {
                    continue;
                }
                analyticsPayloadToCoin(data).ifPresent(coins::add);
            } catch (Exception ignored) {
                // skip broken analytics JSON
            }
        }
        return coins;
    }

    public static Optional<Coin> analyticsPayloadToCoin(JsonNode data) {
        String name = unescapeHtml(textOrEmpty(data.get("item_name")).strip());
        if (name.isEmpty()) {
            return Optional.empty();
        }

        String itemId = textOrNull(data.get("item_id"));
        String metal = normalizeMetal(textOrEmpty(data.get("item_variant")).strip());
        if (metal == null) {
            metal = normalizeMetal(name);
        }
        Double weightG = parseWeightG(name);

        Double regular = PriceParser.parseRub(textOrNull(data.get("price")));
        Double card = PriceParser.parseRub(textOrNull(data.get("cardprice")));
        Double sellPrice = regular != null ? regular : card;
        JsonNode availability = data.get("availability");
        if (availability != null && availability.isBoolean() && !availability.asBoolean()) {
            sellPrice = null;
        }

        return Optional.of(new Coin(itemId, name, metal, weightG, null, sellPrice));
    }

    public static String normalizeMetal(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String s = value.strip().toLowerCase();
        if (METAL_MAP.containsKey(s)) {
            return METAL_MAP.get(s);
        }
        for (Map.Entry<String, String> entry : METAL_MAP.entrySet()) {
            if (s.contains(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static Double parseWeightG(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = WEIGHT_RE.matcher(text.toLowerCase());
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group(1).replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String dedupeKey(Coin coin, String itemId) {
        if (itemId != null && !itemId.isBlank()) {
            return "id:" + itemId;
        }
        return "fb:" + coin.name() + "|" + coin.weightG() + "|" + coin.metal();
    }

    public static String dedupeKey(JsonNode data, Coin coin) {
        return dedupeKey(coin, textOrNull(data.get("item_id")));
    }

    public static boolean isCaptchaTitle(String title) {
        return title != null && title.toLowerCase().contains("captcha");
    }

    public static boolean isCaptchaBody(String body) {
        if (body == null) {
            return false;
        }
        String low = body.toLowerCase();
        return low.contains("не робот")
                || low.contains("not a robot")
                || low.contains("ползунк")
                || low.contains("выровнять картинку");
    }

    /** Антибот-заглушка без analytics payload. */
    public static boolean isCaptchaInterstitial(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        if (hasAnalyticsPayload(html)) {
            return false;
        }
        String low = html.toLowerCase();
        return low.contains("captcha") || low.contains("gorizontal-vertikal");
    }

    private static String unescapeHtml(String text) {
        return text.replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText();
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
