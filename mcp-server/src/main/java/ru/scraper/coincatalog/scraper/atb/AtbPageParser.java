package ru.scraper.coincatalog.scraper.atb;

import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.common.PriceParser;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AtbPageParser {

    public static final String CATALOG_URL = "https://www.atb.su/vklady-i-scheta/monety/";
    public static final String BASE_URL = "https://www.atb.su";
    public static final String INVESTMENT_CATEGORY = "479";

    private static final Pattern METAL_LINE_RE = Pattern.compile(
            "^(золото|серебро|платина|палладий|медно-никелевый сплав)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CARD_PATTERN = Pattern.compile(
            "<a\\s+class=\"coins-item[^\"]*\"\\s+href=\"(/vklady-i-scheta/monety/[^\"]+/?)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern NAME_PATTERN = Pattern.compile(
            "class=\"coins-item__name\"\\s*>(.*?)</div>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern PRICE_PATTERN = Pattern.compile(
            "class=\"coins-item__price\"\\s*>(.*?)</(?:motion\\.)?div>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DETAIL_ROW_PATTERN = Pattern.compile(
            "<tr>\\s*<td>(.*?)</td>\\s*<td>(.*?)</td>\\s*</tr>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WEIGHT_PATTERN = Pattern.compile("\\d+(?:[.,]\\d+)?");

    private AtbPageParser() {}

    public static String resolveCategory(boolean investmentOnly) {
        return investmentOnly ? INVESTMENT_CATEGORY : "";
    }

    public static String buildRequestBody(String category, String query) {
        String nameParam = "";
        if (query != null && !query.isBlank()) {
            nameParam = URLEncoder.encode(query.strip(), StandardCharsets.UTF_8);
        }
        return "ajax=true&"
                + "category=" + (category != null ? category : "") + "&"
                + "type=js-coins&"
                + "country=&"
                + "metall=&"
                + "sample=&"
                + "denomination=&"
                + "year=&"
                + "count=99999&"
                + "name=" + nameParam + "&"
                + "page=1";
    }

    public static boolean detectCaptcha(String html) {
        if (html == null) {
            return false;
        }
        String lower = html.toLowerCase();
        return lower.contains("не робот")
                || lower.contains("not a robot")
                || lower.contains("captcha")
                || lower.contains("ползунк");
    }

    public static boolean isEmptySearchResult(String fragmentHtml) {
        if (fragmentHtml == null) {
            return false;
        }
        String lower = fragmentHtml.toLowerCase();
        return fragmentHtml.contains("coins-select__no-result") || lower.contains("ничего не найдено");
    }

    public static List<CardMatch> parseCardsFromFragment(String fragmentHtml) {
        List<CardMatch> cards = new ArrayList<>();
        Matcher matcher = CARD_PATTERN.matcher(fragmentHtml);
        while (matcher.find()) {
            cards.add(new CardMatch(matcher.group(1), matcher.group(2)));
        }
        return cards;
    }

    public static Optional<ParsedCard> parseCard(String cardHtml, String href, String detailHtml) {
        Matcher nameMatcher = NAME_PATTERN.matcher(cardHtml);
        if (!nameMatcher.find()) {
            return Optional.empty();
        }
        String name = stripTags(nameMatcher.group(1));
        if (name.isBlank()) {
            return Optional.empty();
        }

        Double sellPrice = null;
        Matcher priceMatcher = PRICE_PATTERN.matcher(cardHtml);
        if (priceMatcher.find()) {
            sellPrice = PriceParser.parseRub(stripTags(priceMatcher.group(1)));
        }

        String coinUrl = resolveUrl(href);
        DetailFields detail = parseDetailFields(detailHtml);

        return Optional.of(new ParsedCard(
                new Coin(detail.catalogNumber(), name, detail.metal(), detail.weightG(), null, sellPrice),
                coinUrl));
    }

    public static DetailFields parseDetailFields(String detailHtml) {
        Map<String, String> kv = new HashMap<>();
        Matcher matcher = DETAIL_ROW_PATTERN.matcher(detailHtml);
        while (matcher.find()) {
            String key = stripTags(matcher.group(1));
            String value = stripTags(matcher.group(2));
            if (!key.isBlank() && !value.isBlank()) {
                kv.put(key.toLowerCase(), value);
            }
        }

        String catalogNumber = kv.get("каталожный номер");
        String metal = normalizeMetal(kv.getOrDefault("металл, проба", kv.get("металл")));
        String weightRaw = firstNonBlank(kv, "масса общая, г", "масса, г", "масса");
        Double weightG = parseWeightG(weightRaw);
        return new DetailFields(catalogNumber, metal, weightG);
    }

    public static String normalizeMetal(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = METAL_LINE_RE.matcher(text.strip());
        if (matcher.find()) {
            String metal = matcher.group(1);
            return metal.substring(0, 1).toUpperCase() + metal.substring(1).toLowerCase();
        }
        return null;
    }

    static Double parseWeightG(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = WEIGHT_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            return Double.parseDouble(matcher.group().replace(',', '.'));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static String stripTags(String html) {
        String text = Pattern.compile("<br\\s*/?>", Pattern.CASE_INSENSITIVE).matcher(html).replaceAll("\n");
        text = Pattern.compile("</p\\s*>", Pattern.CASE_INSENSITIVE).matcher(text).replaceAll("\n");
        text = TAG_PATTERN.matcher(text).replaceAll(" ");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#34;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("\\s+", " ").strip();
    }

    private static String resolveUrl(String href) {
        if (href.startsWith("http")) {
            return href;
        }
        return URI.create(BASE_URL).resolve(href).toString();
    }

    private static String firstNonBlank(Map<String, String> kv, String... keys) {
        for (String key : keys) {
            String value = kv.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    public record CardMatch(String href, String cardHtml) {}

    public record DetailFields(String catalogNumber, String metal, Double weightG) {}

    public record ParsedCard(Coin coin, String url) {}
}
