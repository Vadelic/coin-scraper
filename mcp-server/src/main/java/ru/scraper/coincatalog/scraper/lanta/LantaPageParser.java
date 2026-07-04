package ru.scraper.coincatalog.scraper.lanta;

import lombok.experimental.UtilityClass;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.common.PriceParser;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class LantaPageParser {

    public static final String BASE_URL = "https://www.lanta.ru";
    public static final String CATALOG_URL = BASE_URL + "/petersburg/metals/coins/";
    public static final String INVESTMENT_CATALOG_URL =
            BASE_URL + "/petersburg/metals/coins/ivesticyonnie-monety/";
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
            "you're not a robot",
            "ползунк",
            "выровнять картинку",
            "align the image");

    public record ListItem(
            String id,
            String formId,
            String name,
            String sellRaw,
            String buyRaw,
            boolean outOfStock,
            List<String> info) {}

    public static String resolveCatalogUrl(boolean investmentOnly) {
        return investmentOnly ? INVESTMENT_CATALOG_URL : CATALOG_URL;
    }

    public static String collectListJs() {
        return """
                () => [...document.querySelectorAll(%s)].map(li => {
                  const cont = li.querySelector('.coinList-cont');
                  const priceEl = cont?.querySelector('.coinList-price');
                  let sellRaw = '';
                  if (priceEl) {
                    const clone = priceEl.cloneNode(true);
                    clone.querySelectorAll('small').forEach(s => s.remove());
                    sellRaw = (clone.innerText || '').replace(/\\s+/g, ' ').trim();
                  }
                  const buyRaw = priceEl?.querySelector('small')?.innerText?.trim() || '';
                  const info = [...(cont?.querySelectorAll('.coinList-infoFull li') || [])]
                    .map(el => (el.innerText || '').replace(/\\s+/g, ' ').trim())
                    .filter(Boolean);
                  return {
                    id: li.getAttribute('data-id'),
                    formId: li.getAttribute('data-form-id') || '892',
                    name: cont?.querySelector('.coinList-title')?.innerText?.trim() || '',
                    sellRaw,
                    buyRaw,
                    out: priceEl?.classList.contains('out') || false,
                    info,
                  };
                })
                """
                .formatted(jsonString(COIN_ITEM_SELECTOR));
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
        String name = item.name() != null ? item.name().strip() : "";
        if (name.isEmpty()) {
            return Optional.empty();
        }
        List<String> infoLines = item.info() != null ? item.info() : List.of();
        var prices = parseListPrices(item.sellRaw(), item.buyRaw(), item.outOfStock());
        return Optional.of(new Coin(
                catalogNumber,
                name,
                metalFromInfoLines(infoLines),
                weightGFromInfoLines(infoLines),
                prices.buyPrice(),
                prices.sellPrice()));
    }

    public static ListPrices parseListPrices(String sellRaw, String buyRaw, boolean outOfStock) {
        Double buyPrice = PriceParser.parseRub(buyRaw);
        Double sellPrice = null;
        if (sellRaw != null && !sellRaw.isBlank()) {
            String normalizedSell = normalize(sellRaw);
            boolean markedOut = OUT_OF_STOCK_MARKERS.stream().anyMatch(normalizedSell::contains);
            if (!markedOut) {
                sellPrice = PriceParser.parseRub(sellRaw);
            }
        }
        if (outOfStock) {
            sellPrice = null;
        }
        return new ListPrices(buyPrice, sellPrice);
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

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    public record ListPrices(Double buyPrice, Double sellPrice) {}
}
