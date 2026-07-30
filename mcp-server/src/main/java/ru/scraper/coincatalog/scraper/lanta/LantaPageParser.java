package ru.scraper.coincatalog.scraper.lanta;

import lombok.experimental.UtilityClass;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.PriceParser;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class LantaPageParser {

    public static final String BASE_URL = "https://www.lanta.ru";
    public static final String CATALOG_URL = BASE_URL + "/metals/coins/";
    public static final String INVESTMENT_CATALOG_URL = BASE_URL + "/metals/coins/ivesticyonnie-monety/";
    public static final String POPUP_PATH = "/__ajax/coinPopup.php";
    public static final String COIN_ITEM_SELECTOR = "li.js-openCoinLightbox";
    public static final String COIN_LIST_SELECTOR = ".coinList";

    private static final Pattern ARTICLE_RE = Pattern.compile(
            "артикул\\s*[:：]\\s*([0-9A-Za-zА-Яа-я\\-–—/]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern METAL_LINE_RE = Pattern.compile(
            "^(золото|серебро|платина|палладий)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WEIGHT_INFO_RE = Pattern.compile(
            "масса\\s+(?:ag|au|монеты)\\s*[:：]\\s*(\\d+(?:[,.]\\d+)?)\\s*г",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern WEIGHT_RE = Pattern.compile(
            "\\b(\\d+(?:[,.]\\d+)?)\\s*г(?:р|рамм)?\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final List<String> OUT_OF_STOCK_MARKERS = List.of("нет в продаже", "нет в наличии");
    private static final List<String> CAPTCHA_TITLE_HINTS = List.of("captcha");
    private static final List<String> CAPTCHA_BODY_HINTS = List.of(
            "не робот",
            "not a robot",
            "you're not a robot",
            "ползунк",
            "выровнять картинку",
            "align the image",
            "gorizontal-vertikal",
            "noindex, noarchive");

    private static final Pattern LIST_ITEM_RE = Pattern.compile(
            "<li\\b([^>]*\\bjs-openCoinLightbox\\b[^>]*)>([\\s\\S]*?)(?=<li\\b[^>]*\\bjs-openCoinLightbox\\b|\\z)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_ID_RE = Pattern.compile(
            "data-id=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern ATTR_FORM_RE = Pattern.compile(
            "data-form-id=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_RE = Pattern.compile(
            "class=\"[^\"]*coinList-title[^\"]*\"[^>]*>([\\s\\S]*?)</",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PRICE_BLOCK_RE = Pattern.compile(
            "class=\"([^\"]*coinList-price[^\"]*)\"[^>]*>([\\s\\S]*?)</div>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern INFO_LI_RE = Pattern.compile(
            "class=\"[^\"]*coinList-infoFull[^\"]*\"[^>]*>\\s*<ul[^>]*>([\\s\\S]*?)</ul>",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern LI_TEXT_RE = Pattern.compile(
            "<li[^>]*>([\\s\\S]*?)</li>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SMALL_RE = Pattern.compile(
            "<small[^>]*>([\\s\\S]*?)</small>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG_RE = Pattern.compile("<[^>]+>");

    private static final String EXTRACT_CARD_PRICES_JS = """
            (cont) => {
              const priceBlocks = [...(cont?.querySelectorAll('.coinList-price') || [])];
              const priceEl = priceBlocks.find(el => !el.classList.contains('out'))
                || priceBlocks[priceBlocks.length - 1]
                || null;
              let sellRaw = '';
              if (priceEl) {
                const clone = priceEl.cloneNode(true);
                clone.querySelectorAll('small').forEach(el => el.remove());
                sellRaw = (clone.innerText || '').replace(/\\s+/g, ' ').trim();
              }
              const buyRaw = [...(priceEl?.querySelectorAll('small') || [])]
                .map(s => (s.innerText || '').trim())
                .find(t => /покупка|выкуп/i.test(t))
                || priceEl?.querySelector('small')?.innerText?.trim()
                || '';
              return {
                sellRaw,
                buyRaw,
                out: priceEl?.classList.contains('out') || false,
              };
            }
            """;

    public record ListItem(
            String id,
            String formId,
            String name,
            String sellRaw,
            String buyRaw,
            boolean outOfStock,
            List<String> info) {}

    public record CoinCandidate(ListItem item, Coin coin) {}

    public static String resolveCatalogUrl(boolean investmentOnly) {
        return investmentOnly ? INVESTMENT_CATALOG_URL : CATALOG_URL;
    }

    /** Yandex/anti-bot interstitial без списка монет. */
    public static boolean isCaptchaInterstitial(String html) {
        if (html == null || html.isBlank()) {
            return true;
        }
        if (html.contains("js-openCoinLightbox") || html.contains("coinList")) {
            return false;
        }
        return isCaptchaBody(html) || html.toLowerCase(Locale.ROOT).contains("gorizontal-vertikal");
    }

    /** Парсит карточки списка из HTML каталога (без Playwright evaluate). */
    public static List<ListItem> parseListItemsFromHtml(String html) {
        List<ListItem> items = new ArrayList<>();
        if (html == null || html.isBlank()) {
            return items;
        }
        Matcher itemMatcher = LIST_ITEM_RE.matcher(html);
        while (itemMatcher.find()) {
            String attrs = itemMatcher.group(1);
            String body = itemMatcher.group(2);
            String id = matchGroup(ATTR_ID_RE, attrs);
            String formId = matchGroup(ATTR_FORM_RE, attrs);
            if (formId == null || formId.isBlank()) {
                formId = "892";
            }
            String name = stripHtml(matchGroup(TITLE_RE, body));
            var prices = extractListPricesFromHtml(body);
            List<String> info = extractInfoLines(body);
            items.add(new ListItem(id, formId, name, prices.sellRaw(), prices.buyRaw(), prices.out(), info));
        }
        return items;
    }

    private record RawPrices(String sellRaw, String buyRaw, boolean out) {}

    private static RawPrices extractListPricesFromHtml(String body) {
        Matcher matcher = PRICE_BLOCK_RE.matcher(body);
        String sellRaw = "";
        String buyRaw = "";
        boolean out = false;
        String chosenInner = null;
        boolean chosenOut = false;
        while (matcher.find()) {
            String classes = matcher.group(1);
            String inner = matcher.group(2);
            boolean isOut = classes != null && classes.contains("out");
            if (chosenInner == null || !isOut) {
                chosenInner = inner;
                chosenOut = isOut;
                if (!isOut) {
                    break;
                }
            }
        }
        if (chosenInner != null) {
            out = chosenOut;
            Matcher small = SMALL_RE.matcher(chosenInner);
            while (small.find()) {
                String t = stripHtml(small.group(1));
                if (buyRaw.isEmpty()
                        || t.toLowerCase(Locale.ROOT).contains("покупка")
                        || t.toLowerCase(Locale.ROOT).contains("выкуп")) {
                    buyRaw = t;
                }
            }
            String withoutSmall = SMALL_RE.matcher(chosenInner).replaceAll("");
            sellRaw = stripHtml(withoutSmall);
        }
        return new RawPrices(sellRaw, buyRaw, out);
    }

    private static List<String> extractInfoLines(String body) {
        List<String> lines = new ArrayList<>();
        Matcher block = INFO_LI_RE.matcher(body);
        if (!block.find()) {
            return lines;
        }
        Matcher li = LI_TEXT_RE.matcher(block.group(1));
        while (li.find()) {
            String text = stripHtml(li.group(1));
            if (!text.isBlank()) {
                lines.add(text);
            }
        }
        return lines;
    }

    public static String stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        return TAG_RE.matcher(html).replaceAll(" ").replace('\u00a0', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String matchGroup(Pattern pattern, String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    public static String collectListJs() {
        return """
                () => [...document.querySelectorAll(%s)].map(li => {
                  const cont = li.querySelector('.coinList-cont');
                  const prices = (%s)(cont);
                  const info = [...(cont?.querySelectorAll('.coinList-infoFull li') || [])]
                    .map(el => (el.innerText || '').replace(/\\s+/g, ' ').trim())
                    .filter(Boolean);
                  return {
                    id: li.getAttribute('data-id'),
                    formId: li.getAttribute('data-form-id') || '892',
                    name: cont?.querySelector('.coinList-title')?.innerText?.trim() || '',
                    sellRaw: prices.sellRaw,
                    buyRaw: prices.buyRaw,
                    out: prices.out,
                    info,
                  };
                })
                """
                .formatted(jsonString(COIN_ITEM_SELECTOR), EXTRACT_CARD_PRICES_JS);
    }

    public static String readListItemPricesJs() {
        return """
                (coinId) => {
                  const li = document.querySelector(`li.js-openCoinLightbox[data-id="${coinId}"]`);
                  const cont = li?.querySelector('.coinList-cont');
                  if (!cont) {
                    return null;
                  }
                  return (%s)(cont);
                }
                """
                .formatted(EXTRACT_CARD_PRICES_JS);
    }

    public static String fetchPopupJs() {
        return """
                async ({ path, id, formId }) => {
                  const qs = new URLSearchParams({
                    id: String(id),
                    formId: String(formId),
                    set: 'N',
                    clear_cache: 'Y',
                  });
                  const r = await fetch(`${path}?${qs}`, {
                    credentials: 'include',
                    headers: { 'X-Requested-With': 'XMLHttpRequest' },
                  });
                  if (!r.ok) throw new Error('popup HTTP ' + r.status);
                  return await r.text();
                }
                """;
    }

    public static boolean isCaptchaTitle(String title) {
        if (title == null || title.isBlank()) {
            return false;
        }
        String normalized = title.toLowerCase(Locale.ROOT);
        return CAPTCHA_TITLE_HINTS.stream().anyMatch(normalized::contains);
    }

    public static boolean isCaptchaBody(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String normalized = normalize(body);
        return CAPTCHA_BODY_HINTS.stream().anyMatch(normalized::contains);
    }

    public static String parseArticleFromPopupHtml(String html) {
        if (html == null || html.isBlank()) {
            return null;
        }
        String text = html.replaceAll("<[^>]+>", " ");
        Matcher matcher = ARTICLE_RE.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

    public static String metalFromInfoLines(List<String> lines) {
        if (lines == null) {
            return null;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Matcher matcher = METAL_LINE_RE.matcher(line.strip());
            if (matcher.find()) {
                String name = matcher.group(1);
                return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1).toLowerCase(Locale.ROOT);
            }
        }
        return null;
    }

    public static Double weightGFromInfoLines(List<String> lines) {
        if (lines == null) {
            return null;
        }
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            Matcher matcher = WEIGHT_INFO_RE.matcher(line);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1).replace(',', '.'));
            }
        }
        for (String line : lines) {
            Double weight = parseWeightG(line);
            if (weight != null) {
                return weight;
            }
        }
        return null;
    }

    public static Double parseWeightG(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Matcher matcher = WEIGHT_RE.matcher(text.toLowerCase(Locale.ROOT));
        if (!matcher.find()) {
            return null;
        }
        return Double.parseDouble(matcher.group(1).replace(',', '.'));
    }

    public static Optional<Coin> listItemToCoin(ListItem item, String catalogNumber) {
        return listItemToCoin(item, catalogNumber, null);
    }

    public static Optional<Coin> listItemToCoin(ListItem item, String catalogNumber, String popupHtml) {
        String name = item.name() != null ? item.name().strip() : "";
        if (name.isEmpty()) {
            return Optional.empty();
        }
        List<String> infoLines = item.info() != null ? item.info() : List.of();
        var prices = parseListPrices(item.sellRaw(), item.buyRaw(), item.outOfStock());
        prices = applyPopupTradeSides(prices, popupHtml);
        return Optional.of(new Coin(
                catalogNumber,
                name,
                metalFromInfoLines(infoLines),
                weightGFromInfoLines(infoLines),
                prices.buyPrice(),
                prices.sellPrice()));
    }

    public static ListPrices parseListPrices(String sellRaw, String buyRaw, boolean outOfStock) {
        Double buyPrice = parseBuyPrice(buyRaw);
        Double sellPrice = null;
        if (sellRaw != null && !sellRaw.isBlank()) {
            String normalizedSell = normalize(sellRaw);
            boolean markedOut = OUT_OF_STOCK_MARKERS.stream().anyMatch(normalizedSell::contains);
            if (!markedOut) {
                sellPrice = PriceParser.parseLastRub(sellRaw);
            }
        }
        if (outOfStock) {
            sellPrice = null;
        }
        return new ListPrices(buyPrice, sellPrice);
    }

    /**
     * В popup «Купить» — банк продаёт клиенту (sellPrice), «Продать» — выкуп (buyPrice).
     */
    public static ListPrices applyPopupTradeSides(ListPrices prices, String popupHtml) {
        if (popupHtml == null || popupHtml.isBlank()) {
            return prices;
        }
        boolean canBuyFromBank = popupHtml.contains("data-type=\"Купить\"");
        boolean canSellToBank = popupHtml.contains("data-type=\"Продать\"");
        return new ListPrices(
                canSellToBank ? prices.buyPrice() : null,
                canBuyFromBank ? prices.sellPrice() : null);
    }

    /**
     * Схлопывает дубли одного артикула, когда на сайте «нет в продаже» и «в наличии» — это одна позиция
     * (5217-0048). Если все варианты в наличии (5216-0060 СПМД/ММД) — оставляем отдельными монетами.
     */
    public static List<CoinCandidate> collapseCatalogVariants(List<CoinCandidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<CoinCandidate> collapsed = new ArrayList<>();
        Set<String> processedCatalogs = new HashSet<>();

        for (CoinCandidate candidate : candidates) {
            String catalogNumber = candidate.coin().catalogNumber();
            if (catalogNumber == null || catalogNumber.isBlank()) {
                collapsed.add(candidate);
                continue;
            }
            if (!processedCatalogs.add(catalogNumber)) {
                continue;
            }
            List<CoinCandidate> group = candidates.stream()
                    .filter(c -> catalogNumber.equals(c.coin().catalogNumber()))
                    .toList();
            if (group.size() > 1 && hasMixedAvailability(group)) {
                collapsed.add(mergePreferInStock(group));
            } else {
                collapsed.addAll(group);
            }
        }
        return collapsed;
    }

    public static String dedupeKey(ListItem item, Coin coin) {
        if (item.id() != null && !item.id().isBlank()) {
            return "id:" + item.id();
        }
        if (coin.catalogNumber() != null && !coin.catalogNumber().isBlank()) {
            return "art:" + coin.catalogNumber();
        }
        return "fb:" + coin.name() + "|" + coin.weightG() + "|" + coin.metal();
    }

    private static boolean hasMixedAvailability(List<CoinCandidate> group) {
        boolean hasUnavailable = false;
        boolean hasAvailable = false;
        for (CoinCandidate candidate : group) {
            if (isUnavailableForSale(candidate)) {
                hasUnavailable = true;
            } else {
                hasAvailable = true;
            }
            if (hasUnavailable && hasAvailable) {
                return true;
            }
        }
        return false;
    }

    private static boolean isUnavailableForSale(CoinCandidate candidate) {
        if (candidate.item().outOfStock()) {
            return true;
        }
        if (candidate.coin().sellPrice() == null) {
            return true;
        }
        String sellRaw = candidate.item().sellRaw();
        if (sellRaw == null || sellRaw.isBlank()) {
            return true;
        }
        return OUT_OF_STOCK_MARKERS.stream().anyMatch(normalize(sellRaw)::contains);
    }

    private static CoinCandidate mergePreferInStock(List<CoinCandidate> group) {
        CoinCandidate primary = group.stream()
                .filter(c -> !isUnavailableForSale(c))
                .findFirst()
                .orElse(group.getFirst());
        Coin coin = primary.coin();
        String name = stripMintSuffix(coin.name());
        if (name.isBlank()) {
            name = coin.name();
        }
        Coin merged = new Coin(coin.catalogNumber(), name, coin.metal(), coin.weightG(), coin.buyPrice(), coin.sellPrice());
        return new CoinCandidate(primary.item(), merged);
    }

    private static String stripMintSuffix(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("\\s*\\([^)]*\\)\\s*$", "").strip();
    }

    private static Double parseBuyPrice(String buyRaw) {
        if (buyRaw == null || buyRaw.isBlank()) {
            return null;
        }
        String normalized = normalize(buyRaw);
        if (!normalized.contains("покупка") && !normalized.contains("выкуп")) {
            return null;
        }
        return PriceParser.parseLastRub(buyRaw);
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public record ListPrices(Double buyPrice, Double sellPrice) {}
}
