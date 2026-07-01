package ru.scraper.coincatalog.scraper.aurumex;

import com.fasterxml.jackson.databind.JsonNode;
import ru.scraper.coincatalog.model.Coin;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AurumexPayloadParser {

    public static final String PUBLIC_URL = "https://aurumex.ru";
    public static final String CATALOG_PATH = "/catalog";
    public static final String CATALOG_URL = PUBLIC_URL + CATALOG_PATH + "?availability=true";
    public static final String PAYLOAD_SUFFIX = "?availability=true";
    public static final String PAYLOAD_URL_PAGE1 =
            PUBLIC_URL + CATALOG_PATH + "/_payload.json" + PAYLOAD_SUFFIX;

    public static final int DEFAULT_MAX_PAGES = 10;

    private static final Pattern PACK_COUNT_RE = Pattern.compile("(\\d+)\\s*шт", Pattern.CASE_INSENSITIVE);
    private static final Pattern FLOAT_IN_TEXT_RE = Pattern.compile("\\d+(?:\\.\\d+)?");

    private static final Map<String, String> METAL_MAP = Map.of(
            "gold", "Золото",
            "silver", "Серебро");

    private AurumexPayloadParser() {}

    public static String payloadUrlForPage(int page) {
        if (page <= 1) {
            return PAYLOAD_URL_PAGE1;
        }
        return PUBLIC_URL + CATALOG_PATH + "/page/" + page + "/_payload.json" + PAYLOAD_SUFFIX;
    }

    public static JsonNode derefCell(JsonNode store, JsonNode ptr) {
        if (ptr == null || ptr.isNull()) {
            return ptr;
        }
        if (!ptr.isInt()) {
            return ptr;
        }
        int index = ptr.asInt();
        if (index < 0 || index >= store.size()) {
            return ptr;
        }
        return store.get(index);
    }

    public static Double toFloat(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isBoolean()) {
            return null;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        String text = value.asText().replace('\u00a0', ' ').replace(",", ".").replace(" ", "");
        Matcher matcher = FLOAT_IN_TEXT_RE.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Double parseWeightG(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        String text = value.asText().replace('\u00a0', ' ');
        Matcher matcher = Pattern.compile("\\d+(?:[.,]\\d+)?").matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String categoryToMetal(JsonNode slug) {
        if (slug == null || slug.isNull() || !slug.isTextual()) {
            return null;
        }
        return METAL_MAP.get(slug.asText().strip().toLowerCase());
    }

    public static Integer parsePackCount(String title) {
        Matcher matcher = PACK_COUNT_RE.matcher(title);
        if (!matcher.find()) {
            return null;
        }
        int n = Integer.parseInt(matcher.group(1));
        return n > 1 ? n : null;
    }

    public static List<PriceTier> parsePriceTiers(JsonNode raw, JsonNode store) {
        JsonNode prices = derefCell(store, raw.get("prices"));
        if (prices == null || !prices.isObject()) {
            return List.of();
        }

        List<PriceTier> tiers = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = prices.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            JsonNode tier = derefCell(store, entry.getValue());
            if (tier == null || !tier.isObject()) {
                continue;
            }
            JsonNode fromVal = derefCell(store, tier.get("from"));
            JsonNode toVal = derefCell(store, tier.get("to"));
            Double value = toFloat(derefCell(store, tier.get("value")));
            if ((fromVal == null || fromVal.isNull()) && value == null) {
                continue;
            }
            Object from = parseTierBound(fromVal);
            tiers.add(new PriceTier(from, toVal, value));
        }
        return tiers;
    }

    public static Double resolveSellPrice(
            JsonNode raw,
            JsonNode store,
            String title,
            Double listPrice,
            Double pricePerOunce,
            List<PriceTier> tiers) {
        Integer packN = parsePackCount(title);
        boolean multiTier = tiers.size() >= 2;

        Optional<PriceTier> tierOne = tiers.stream()
                .filter(t -> Integer.valueOf(1).equals(tierFrom(t)) && t.value() != null)
                .findFirst();
        if (tierOne.isPresent()) {
            double val = tierOne.get().value();
            if (!(packN != null && isPackTotal(val, packN, pricePerOunce))) {
                return val;
            }
        }

        if (packN != null && pricePerOunce != null && listPrice != null) {
            if (Math.abs(Math.round(listPrice / pricePerOunce) - packN) <= 1) {
                return pricePerOunce;
            }
            return listPrice / packN;
        }

        if (listPrice != null) {
            return listPrice;
        }

        if (pricePerOunce != null && !multiTier) {
            return pricePerOunce;
        }

        return null;
    }

    public static Double resolveBuyPrice(
            JsonNode raw,
            JsonNode store,
            String title,
            Double listPrice,
            Double pricePerOunce) {
        Double purchase = toFloat(derefCell(store, raw.get("pricePurchase")));
        if (purchase == null) {
            return null;
        }

        Integer packN = parsePackCount(title);
        if (packN != null && pricePerOunce != null && listPrice != null) {
            if (Math.abs(Math.round(listPrice / pricePerOunce) - packN) <= 1) {
                if (Math.abs(Math.round(purchase / pricePerOunce) - packN) <= 1) {
                    return purchase / packN;
                }
            }
        }
        return purchase;
    }

    public static JsonNode findCatalogBlock(JsonNode store) {
        if (!store.isArray()) {
            return null;
        }
        for (JsonNode cell : store) {
            if (cell != null
                    && cell.isObject()
                    && cell.has("coins")
                    && cell.has("totalCoins")
                    && cell.has("categories")) {
                return cell;
            }
        }
        return null;
    }

    public static List<Coin> extractCoinsFromStore(JsonNode store) {
        if (!store.isArray()) {
            throw new IllegalStateException("Неверный формат payload: ожидался массив");
        }

        JsonNode block = findCatalogBlock(store);
        if (block == null) {
            throw new IllegalStateException("Не найден блок каталога с ключом coins в payload");
        }

        JsonNode coinsIndices = derefCell(store, block.get("coins"));
        if (coinsIndices == null || !coinsIndices.isArray()) {
            throw new IllegalStateException("coins не является списком");
        }

        List<Coin> coins = new ArrayList<>();
        for (JsonNode ref : coinsIndices) {
            if (!ref.isInt()) {
                continue;
            }
            JsonNode raw = derefCell(store, ref);
            if (raw == null || !raw.isObject()) {
                continue;
            }
            toCoin(raw, store).ifPresent(coins::add);
        }
        return coins;
    }

    public static boolean coinMatchesQuery(Coin coin, String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return true;
        }
        String qLower = q.toLowerCase();
        if (coin.name().toLowerCase().contains(qLower)) {
            return true;
        }
        return coin.catalogNumber() != null && coin.catalogNumber().toLowerCase().contains(qLower);
    }

    public static String dedupeKey(Coin coin) {
        if (coin.catalogNumber() != null && !coin.catalogNumber().isBlank()) {
            return coin.catalogNumber();
        }
        return coin.name();
    }

    public static boolean isCaptchaTitle(String title) {
        if (title == null) {
            return false;
        }
        return title.toLowerCase().contains("captcha");
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

    private static Optional<Coin> toCoin(JsonNode raw, JsonNode store) {
        try {
            JsonNode isAvailable = derefCell(store, raw.get("isAvailable"));
            if (!isAvailable.isBoolean() || !isAvailable.asBoolean()) {
                return Optional.empty();
            }

            String article = derefCell(store, raw.get("id")).asText();
            JsonNode titleNode = derefCell(store, raw.get("title"));
            if (titleNode == null || !titleNode.isTextual() || titleNode.asText().strip().isEmpty()) {
                return Optional.empty();
            }

            String name = titleNode.asText().strip();
            JsonNode cat = derefCell(store, raw.get("category"));
            JsonNode weight = derefCell(store, raw.get("weight"));
            Double listPrice = toFloat(derefCell(store, raw.get("price")));
            Double pricePerOunce = toFloat(derefCell(store, raw.get("pricePerOunce")));

            List<PriceTier> tiers = parsePriceTiers(raw, store);
            Double sellPrice = resolveSellPrice(raw, store, name, listPrice, pricePerOunce, tiers);
            Double buyPrice = resolveBuyPrice(raw, store, name, listPrice, pricePerOunce);

            return Optional.of(new Coin(
                    article,
                    name,
                    categoryToMetal(cat),
                    parseWeightG(weight),
                    buyPrice,
                    sellPrice));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean isPackTotal(double value, int packN, Double pricePerOunce) {
        if (pricePerOunce == null || pricePerOunce <= 0) {
            return false;
        }
        double ratio = value / pricePerOunce;
        return Math.abs(Math.round(ratio) - packN) <= 1;
    }

    private static Integer tierFrom(PriceTier tier) {
        if (tier.from() instanceof Integer i) {
            return i;
        }
        if (tier.from() instanceof Number n) {
            return n.intValue();
        }
        return null;
    }

    private static Object parseTierBound(JsonNode fromVal) {
        if (fromVal == null || fromVal.isNull()) {
            return null;
        }
        if (fromVal.isInt()) {
            return fromVal.asInt();
        }
        if (fromVal.isNumber()) {
            return fromVal.asInt();
        }
        String text = fromVal.asText().strip();
        if (text.matches("\\d+")) {
            return Integer.parseInt(text);
        }
        return text;
    }

    public record PriceTier(Object from, JsonNode to, Double value) {}
}
