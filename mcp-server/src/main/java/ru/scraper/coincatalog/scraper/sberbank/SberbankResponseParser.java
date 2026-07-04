package ru.scraper.coincatalog.scraper.sberbank;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import ru.scraper.coincatalog.model.Coin;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

public final class SberbankResponseParser {

    public static final String CATALOG_URL = "https://www.sberbank.ru/ru/person/mon";
    public static final String ORIGIN = "https://www.sberbank.ru";
    public static final String API_PATH = "/proxy/services/coin-catalog/coins";
    public static final String API_BUYOUT_PATH = "/proxy/services/coin-catalog/coins/buyout";

    public static final int DEFAULT_PAGE_SIZE = 4000;
    public static final String DEFAULT_CITY = "Москва";
    public static final int DEFAULT_CONDITION = 1;
    public static final String INVESTMENT_SECTION = "Инвестиционные монеты";

    public static final List<String> DEFAULT_METAL_FILTERS =
            List.of("Золото", "Серебро", "Платина", "Палладий");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SberbankResponseParser() {}

    public static ObjectNode buildPayload(
            int page,
            int pageSize,
            String city,
            int condition,
            String query,
            List<String> metals,
            List<String> sections,
            List<String> categories) {
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("query", query != null ? query : "");
        payload.put("priceSellMin", 0);
        payload.put("priceSellMax", 0);
        payload.put("parMin", 0);
        payload.put("parMax", 0);
        payload.put("massMin", 0);
        payload.put("massMax", 0);
        payload.putArray("metals").addAll(toArrayNode(metals));
        payload.putArray("sections").addAll(toArrayNode(sections));
        payload.putArray("categories").addAll(toArrayNode(categories));
        payload.put("condition", condition);
        payload.putNull("vspCode");
        payload.put("inDiscount", false);
        payload.put("page", page);
        payload.put("pageSize", pageSize);
        payload.put("city", city);
        return payload;
    }

    public static List<String> resolveSections(boolean investmentOnly) {
        if (investmentOnly) {
            return List.of(INVESTMENT_SECTION);
        }
        return List.of();
    }

    public static String buildBuyoutPath(int page, int pageSize, String query) {
        String qs = "query=" + urlEncode(query != null ? query : "")
                + "&page=" + page
                + "&pageSize=" + pageSize;
        return API_BUYOUT_PATH + "?" + qs;
    }

    public static String absoluteUrl(String pathOrUrl) {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl;
        }
        return ORIGIN + pathOrUrl;
    }

    public static List<ObjectNode> entitiesFromCatalogResponse(JsonNode data) {
        if (!data.isObject()) {
            throw new IllegalStateException("Ответ каталога: ожидался объект JSON");
        }
        JsonNode entities = data.get("entities");
        if (entities == null || !entities.isArray()) {
            return List.of();
        }
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode node : entities) {
            if (node.isObject()) {
                result.add((ObjectNode) node.deepCopy());
            }
        }
        return result;
    }

    public static MergeResult mergeEntitiesWithMetalFilters(List<MetalBatch> perMetalResponses) {
        List<ObjectNode> merged = new ArrayList<>();
        Map<String, String> idToMetal = new LinkedHashMap<>();
        int rawRows = 0;
        for (MetalBatch batch : perMetalResponses) {
            for (ObjectNode row : batch.entities()) {
                rawRows++;
                JsonNode idNode = row.get("id");
                if (idNode == null || idNode.isNull()) {
                    continue;
                }
                String sid = idNode.asText().strip();
                if (sid.isEmpty()) {
                    continue;
                }
                if (idToMetal.containsKey(sid)) {
                    continue;
                }
                idToMetal.put(sid, batch.metal());
                ObjectNode copy = row.deepCopy();
                copy.put("metal", batch.metal());
                merged.add(copy);
            }
        }
        return new MergeResult(merged, rawRows);
    }

    public static List<JsonNode> entitiesFromBuyoutResponse(JsonNode data) {
        if (data.isArray()) {
            List<JsonNode> result = new ArrayList<>();
            data.forEach(result::add);
            return result;
        }
        if (data.isObject()) {
            for (String key : List.of("entities", "items", "data")) {
                JsonNode value = data.get(key);
                if (value != null && value.isArray()) {
                    List<JsonNode> result = new ArrayList<>();
                    value.forEach(result::add);
                    return result;
                }
            }
        }
        return List.of();
    }

    public static Double buyoutPriceFromEntity(JsonNode entity) {
        for (String key : List.of("priceBuy", "buyoutPrice", "buyPrice", "price")) {
            Double value = toFloat(entity.get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public static void mergeBuyoutIntoCatalog(List<ObjectNode> catalog, List<JsonNode> buyoutRows) {
        Map<String, Double> prices = new LinkedHashMap<>();
        for (JsonNode row : buyoutRows) {
            JsonNode idNode = row.get("id");
            if (idNode == null || idNode.isNull()) {
                continue;
            }
            Double price = buyoutPriceFromEntity(row);
            if (price != null) {
                prices.put(idNode.asText(), price);
            }
        }
        for (ObjectNode row : catalog) {
            JsonNode idNode = row.get("id");
            if (idNode == null || idNode.isNull()) {
                continue;
            }
            String sid = idNode.asText();
            if (prices.containsKey(sid)) {
                row.put("priceBuy", prices.get(sid));
            }
        }
    }

    public static Optional<Coin> entityToCoin(JsonNode entity) {
        JsonNode idNode = entity.get("id");
        if (idNode == null || idNode.isNull()) {
            return Optional.empty();
        }
        String sid = idNode.asText().strip();
        if (sid.isEmpty()) {
            return Optional.empty();
        }
        String name = textOrEmpty(entity.get("name")).strip();
        if (name.isEmpty()) {
            name = sid;
        }
        return Optional.of(new Coin(
                entityCatalogNumber(entity, sid),
                name,
                metalLabelFromEntity(entity),
                toFloat(entity.get("mass1")),
                toFloat(entity.get("priceBuy")),
                toFloat(entity.get("price"))));
    }

    public static boolean coinMatchesQuery(Coin coin, String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return true;
        }
        String hay = coinHaystack(coin);
        String qLower = q.toLowerCase(Locale.ROOT);
        if (hay.contains(qLower)) {
            return true;
        }
        String[] tokens = qLower.split("\\s+");
        if (tokens.length == 1) {
            return tokenMatches(tokens[0], hay);
        }
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (!tokenMatches(token, hay)) {
                return false;
            }
        }
        return true;
    }

    private static String coinHaystack(Coin coin) {
        return String.join(
                        " ",
                        coin.name(),
                        coin.catalogNumber() != null ? coin.catalogNumber() : "",
                        coin.metal() != null ? coin.metal() : "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean tokenMatches(String token, String hay) {
        if (hay.contains(token)) {
            return true;
        }
        // На витрине Сбера серебряный «Георгий Победоносец» часто называется просто «Победоносец».
        if (isGeorgiyToken(token) && hay.contains("победоносец")) {
            return true;
        }
        return false;
    }

    private static boolean isGeorgiyToken(String token) {
        return token.startsWith("георг") || token.startsWith("georg");
    }

    public static String dedupeKey(Coin coin) {
        if (coin.catalogNumber() != null && !coin.catalogNumber().isBlank()) {
            return coin.catalogNumber();
        }
        return coin.name();
    }

    private static String entityCatalogNumber(JsonNode entity, String coinId) {
        for (String key : List.of(
                "catalogNumber", "catalogNum", "cbrCatalogNumber", "article", "sku", "code")) {
            JsonNode raw = entity.get(key);
            if (raw != null && !raw.isNull()) {
                String text = raw.asText().strip();
                if (!text.isEmpty()) {
                    return text;
                }
            }
        }
        String stripped = coinId.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    private static String metalLabelFromEntity(JsonNode entity) {
        JsonNode raw = entity.get("metal");
        if (raw == null || raw.isNull()) {
            return null;
        }
        String text = raw.asText().strip();
        return text.isEmpty() ? null : text;
    }

    private static Double toFloat(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        try {
            return Double.parseDouble(value.asText().strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String textOrEmpty(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        return node.asText();
    }

    private static ArrayNode toArrayNode(List<String> values) {
        ArrayNode array = MAPPER.createArrayNode();
        if (values != null) {
            values.forEach(array::add);
        }
        return array;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record MetalBatch(String metal, List<ObjectNode> entities) {}

    public record MergeResult(List<ObjectNode> entities, int rawRowCount) {}
}
