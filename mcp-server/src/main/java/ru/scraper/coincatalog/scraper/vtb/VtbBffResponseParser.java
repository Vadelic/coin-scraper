package ru.scraper.coincatalog.scraper.vtb;

import lombok.experimental.UtilityClass;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import ru.scraper.coincatalog.model.Coin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class VtbBffResponseParser {

    public static final String BASE_SITE = "https://www.vtb.ru";
    public static final String LIST_PATH = "/api/bff/api/v1/coin/list";
    public static final String COIN_CATALOG_URL =
            BASE_SITE + "/personal/vklady-i-scheta/monety-iz-dragotsennyih-metallov/";

    public static final String COIN_KIND_FILTER_ID = "coinKind";
    public static final String INVESTMENT_KIND_VALUE = "Инвестиционные";

    private static final Pattern METAL_LINE_RE = Pattern.compile(
            "^(золото|серебро|платина|палладий)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static String buildListUrl(int page) {
        return BASE_SITE + LIST_PATH + "?page=" + page;
    }

    public static List<ObjectNode> buildInvestmentFilters() {
        ObjectNode filter = MAPPER.createObjectNode();
        filter.put("id", COIN_KIND_FILTER_ID);
        ArrayNode values = filter.putArray("values");
        values.add(INVESTMENT_KIND_VALUE);
        return List.of(filter);
    }

    public static ObjectNode buildPayload(List<ObjectNode> filters) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode filtersNode = payload.putArray("filters");
        if (filters != null) {
            filters.forEach(filtersNode::add);
        }
        return payload;
    }

    public static List<ObjectNode> resolveApiFilters(boolean investmentOnly) {
        if (investmentOnly) {
            return buildInvestmentFilters();
        }
        return List.of();
    }

    public static ListPageResult parseListResponse(JsonNode body) {
        if (body == null || !body.isObject()) {
            return new ListPageResult(List.of(), 1);
        }
        JsonNode coinsNode = body.get("coins");
        List<JsonNode> coins = new ArrayList<>();
        if (coinsNode != null && coinsNode.isArray()) {
            coinsNode.forEach(coins::add);
        }
        int maxPage = 1;
        JsonNode maxPageNode = body.get("maxPage");
        if (maxPageNode != null && !maxPageNode.isNull()) {
            try {
                maxPage = Math.max(1, maxPageNode.asInt());
            } catch (NumberFormatException ignored) {
                maxPage = 1;
            }
        }
        return new ListPageResult(coins, maxPage);
    }

    public static String normalizeMetal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = METAL_LINE_RE.matcher(text.strip());
        if (matcher.find()) {
            String name = matcher.group(1);
            return name.substring(0, 1).toUpperCase() + name.substring(1).toLowerCase();
        }
        String stripped = text.strip();
        return stripped.isEmpty() ? null : stripped;
    }

    public static Optional<Coin> rowToCoin(JsonNode item) {
        if (item == null || !item.isObject()) {
            return Optional.empty();
        }
        String name = textOrEmpty(item.get("name")).strip();
        String article = textOrNull(item.get("article"));
        if (name.isEmpty() && (article == null || article.isBlank())) {
            return Optional.empty();
        }
        String coinId = textOrNull(item.get("id"));
        String metalRaw = textOrEmpty(item.get("metal")).strip();
        return Optional.of(new Coin(
                article,
                name.isEmpty() ? article : name,
                normalizeMetal(metalRaw.isEmpty() ? null : metalRaw),
                toFloat(item.get("mass")),
                null,
                toFloat(item.get("price1"))));
    }

    public static boolean coinMatchesQuery(Coin coin, String query) {
        String q = query == null ? "" : query.strip();
        if (q.isEmpty()) {
            return true;
        }
        String hay = String.join(
                        " ",
                        coin.name(),
                        coin.catalogNumber() != null ? coin.catalogNumber() : "",
                        coin.metal() != null ? coin.metal() : "")
                .toLowerCase();
        return hay.contains(q.toLowerCase());
    }

    public static String dedupeKey(JsonNode row, Coin coin) {
        String id = textOrNull(row.get("id"));
        if (id != null && !id.isBlank()) {
            return id;
        }
        if (coin.catalogNumber() != null && !coin.catalogNumber().isBlank()) {
            return coin.catalogNumber();
        }
        return coin.name();
    }

    public static boolean isCaptchaTitle(String title) {
        if (title == null) {
            return false;
        }
        String low = title.toLowerCase();
        return low.contains("captcha") || low.contains("robot") || low.contains("робот");
    }

    public static boolean isCaptchaBody(String body) {
        if (body == null) {
            return false;
        }
        String low = body.toLowerCase();
        return low.contains("не робот")
                || low.contains("not a robot")
                || low.contains("проверка безопасности")
                || low.contains("access denied");
    }

    private static Double toFloat(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isNumber()) {
            return value.doubleValue();
        }
        String text = value.asText().strip();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(text);
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

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText().strip();
        return text.isEmpty() ? null : text;
    }

    public record ListPageResult(List<JsonNode> coins, int maxPage) {}
}
