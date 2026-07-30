package ru.scraper.coincatalog.scraper.rshb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.RequestOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.PlaywrightBrowserLauncher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

/** Скрапер каталога РСХБ через Playwright и REST API. */
@Slf4j
@Service("RSHB")
public class RshbScraper implements CoinScraper<Coin> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final List<String> LAUNCH_ARGS = List.of("--no-sandbox", "--disable-setuid-sandbox");
    private static final Set<String> BLOCKED_RESOURCE_TYPES = Set.of("image", "media", "font");
    private static final int BUYOUT_SKU_BATCH_SIZE = 50;

    private final PlaywrightBrowserLauncher browserLauncher;
    private final boolean headless;
    private final Duration timeout;
    private final int retries;
    private final double pageDelaySeconds;
    private final int pageSize;
    private final BiFunction<PageLoadRequest, Page, Boolean> pageLoaderOverride;

    public RshbScraper() {
        this(new PlaywrightBrowserLauncher(), null);
    }

    @Autowired
    public RshbScraper(PlaywrightBrowserLauncher browserLauncher) {
        this(browserLauncher, null);
    }

    RshbScraper(BiFunction<PageLoadRequest, Page, Boolean> pageLoaderOverride) {
        this(new PlaywrightBrowserLauncher(), pageLoaderOverride);
    }

    RshbScraper(
            PlaywrightBrowserLauncher browserLauncher,
            BiFunction<PageLoadRequest, Page, Boolean> pageLoaderOverride) {
        this(browserLauncher, true, Duration.ofMillis(30_000), 3, 5.0, RshbPageParser.DEFAULT_PAGE_SIZE, pageLoaderOverride);
    }

    RshbScraper(
            PlaywrightBrowserLauncher browserLauncher,
            boolean headless,
            Duration timeout,
            int retries,
            double pageDelaySeconds,
            int pageSize,
            BiFunction<PageLoadRequest, Page, Boolean> pageLoaderOverride) {
        this.browserLauncher = browserLauncher;
        this.headless = headless;
        this.timeout = timeout;
        this.retries = Math.max(1, retries);
        this.pageDelaySeconds = Math.max(0, pageDelaySeconds);
        this.pageSize = pageSize > 0 ? pageSize : RshbPageParser.DEFAULT_PAGE_SIZE;
        this.pageLoaderOverride = pageLoaderOverride;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";
        String regionCode = resolveRegionCode(region, investmentOnly);

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            try (browser) {
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(USER_AGENT)
                        .setLocale("ru-RU")
                        .setViewportSize(1280, 800)
                        .setExtraHTTPHeaders(Map.of(
                                "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")));
                context.addCookies(List.of(new com.microsoft.playwright.options.Cookie(
                                RshbPageParser.REGION_COOKIE_NAME,
                                regionCode)
                        .setDomain("coins.rshb.ru")
                        .setPath("/")));
                log.info("Регион каталога: {}={}", RshbPageParser.REGION_COOKIE_NAME, regionCode);

                context.route("**/*", route -> {
                    if (BLOCKED_RESOURCE_TYPES.contains(route.request().resourceType())) {
                        route.abort();
                    } else {
                        route.resume();
                    }
                });

                Page page = context.newPage();
                page.setDefaultTimeout(timeout.toMillis());

                List<Coin> allCoins = new ArrayList<>();
                Set<String> seenUrls = new HashSet<>();
                int processed = 0;
                Integer lastPage = null;
                int pageNum = 1;

                while (true) {
                    if (processed > 0 && pageDelaySeconds > 0) {
                        sleep(pageDelaySeconds);
                    }

                    log.info("Загружаю страницу {}{}", pageNum, lastPage != null ? "/" + lastPage : "");
                    boolean loaded = loadPage(page, pageNum, normalizedQuery, investmentOnly);
                    if (!loaded) {
                        log.error("Страница {} не загрузилась — пропуск", pageNum);
                        if (processed == 0) {
                            break;
                        }
                        pageNum++;
                        if (lastPage != null && pageNum > lastPage) {
                            break;
                        }
                        continue;
                    }

                    sleep(0.25);

                    if (lastPage == null) {
                        lastPage = detectLastPage(page);
                        log.info("Всего страниц: {} (page_size={})", lastPage, pageSize);
                    }

                    List<RshbPageParser.ParsedCard> pageCards = extractCoinsFromPage(page);
                    enrichBuyPrices(context.request(), pageCards);

                    List<Coin> newCoins = new ArrayList<>();
                    for (RshbPageParser.ParsedCard parsed : pageCards) {
                        String key = parsed.url();
                        if (!seenUrls.add(key)) {
                            log.warn(
                                    "Дубликат карточки (url {}): «{}», артикул={}",
                                    key,
                                    parsed.coin().name(),
                                    parsed.coin().catalogNumber());
                            continue;
                        }
                        if (parsed.coin().sellPrice() == null) {
                            log.warn(
                                    "Нет sell_price для «{}» (артикул {})",
                                    parsed.coin().name(),
                                    parsed.coin().catalogNumber());
                        }
                        newCoins.add(parsed.coin());
                    }
                    allCoins.addAll(newCoins);
                    processed++;

                    log.info(
                            "Страница {}/{}: {} монет (новых: {}, всего: {})",
                            pageNum,
                            lastPage,
                            pageCards.size(),
                            newCoins.size(),
                            allCoins.size());

                    if (pageCards.isEmpty()) {
                        log.info("Страница {} пустая — остановка", pageNum);
                        break;
                    }
                    if (pageNum >= lastPage) {
                        break;
                    }
                    pageNum++;
                }

                return new ScrapePayload(processed, allCoins);
            }
        }
    }

    private boolean loadPage(Page page, int pageNum, String searchText, boolean investmentOnly) {
        if (pageLoaderOverride != null) {
            return pageLoaderOverride.apply(new PageLoadRequest(pageNum, searchText, investmentOnly), page);
        }

        String url = RshbPageParser.buildUrl(pageNum, pageSize, searchText, investmentOnly);
        if (investmentOnly) {
            log.info("Фильтр: только инвестиционные монеты (subjects={})", RshbPageParser.INVESTMENT_SUBJECTS);
        }
        if (!searchText.isBlank()) {
            log.info("Поиск search_text=«{}»", searchText);
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                page.navigate(
                        url,
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(timeout.toMillis()));
                page.waitForSelector(RshbPageParser.CARD_LINK_SELECTOR);
                try {
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
                } catch (PlaywrightException e) {
                    log.debug("страница {}: networkidle не наступил — продолжаю", pageNum);
                }
                return true;
            } catch (Exception e) {
                lastError = e;
                log.warn("Страница {}, попытка {}/{} — ошибка: {}", pageNum, attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(Math.pow(2, attempt));
                }
            }
        }
        log.error("Страница {}: все попытки исчерпаны: {}", pageNum, lastError != null ? lastError.getMessage() : "");
        return false;
    }

    private int detectLastPage(Page page) {
        List<String> hrefs = new ArrayList<>();
        for (ElementHandle link : page.querySelectorAll(RshbPageParser.PAGINATION_LINK_SELECTOR)) {
            String href = link.getAttribute("href");
            if (href != null) {
                hrefs.add(href);
            }
        }
        return RshbPageParser.parsePaginationMax(hrefs);
    }

    private List<RshbPageParser.ParsedCard> extractCoinsFromPage(Page page) {
        List<RshbPageParser.ParsedCard> coins = new ArrayList<>();
        for (ElementHandle link : page.querySelectorAll(RshbPageParser.CARD_LINK_SELECTOR)) {
            String href = link.getAttribute("href");
            if (href == null) {
                href = "";
            }
            String rawText;
            String linkText;
            String priceBoxText;
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> block =
                        (Map<String, Object>) link.evaluate(RshbPageParser.cardTextJs());
                rawText = stringOrEmpty(block.get("card"));
                if (rawText.isBlank()) {
                    rawText = stringOrEmpty(block.get("link"));
                }
                linkText = stringOrEmpty(block.get("link"));
                priceBoxText = stringOrEmpty(block.get("priceBox"));
            } catch (PlaywrightException e) {
                rawText = link.innerText();
                linkText = rawText;
                priceBoxText = "";
            }

            var parsed = RshbPageParser.parseCard(
                    new RshbPageParser.CardInput(rawText, href, linkText, priceBoxText));
            if (parsed.isEmpty()) {
                log.warn("Пропуск карточки: не удалось распарсить href={}", href);
                continue;
            }
            if (parsed.get().coin().name() == null || parsed.get().coin().name().isBlank()) {
                log.warn("Пропуск карточки: пустое название, url={}", parsed.get().url());
                continue;
            }
            coins.add(parsed.get());
        }
        return coins;
    }

    private void enrichBuyPrices(APIRequestContext request, List<RshbPageParser.ParsedCard> cards) {
        List<String> skus = new ArrayList<>();
        for (RshbPageParser.ParsedCard card : cards) {
            Coin coin = card.coin();
            if (coin.catalogNumber() != null && coin.buyPrice() == null) {
                skus.add(coin.catalogNumber());
            }
        }
        if (skus.isEmpty()) {
            return;
        }
        Map<String, Double> buyoutBySku = fetchBuyoutPrices(request, skus);
        if (buyoutBySku.isEmpty()) {
            return;
        }

        for (int i = 0; i < cards.size(); i++) {
            RshbPageParser.ParsedCard card = cards.get(i);
            Coin coin = card.coin();
            if (coin.buyPrice() != null || coin.catalogNumber() == null) {
                continue;
            }
            Double buyPrice = buyoutBySku.get(coin.catalogNumber());
            if (buyPrice != null) {
                cards.set(
                        i,
                        new RshbPageParser.ParsedCard(
                                new Coin(
                                        coin.catalogNumber(),
                                        coin.name(),
                                        coin.metal(),
                                        coin.weightG(),
                                        buyPrice,
                                        coin.sellPrice()),
                                card.url()));
            }
        }
    }

    private Map<String, Double> fetchBuyoutPrices(APIRequestContext request, List<String> skus) {
        List<String> unique = skus.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, Double> registry = new HashMap<>();
        if (unique.isEmpty()) {
            return registry;
        }

        for (int offset = 0; offset < unique.size(); offset += BUYOUT_SKU_BATCH_SIZE) {
            List<String> batch = unique.subList(offset, Math.min(offset + BUYOUT_SKU_BATCH_SIZE, unique.size()));
            Map<String, Object> payload = Map.of(
                    "query", Map.of("terms", Map.of("sku", batch)),
                    "size", batch.size());
            try {
                String body = MAPPER.writeValueAsString(payload);
                APIResponse response = request.post(
                        RshbPageParser.PRODUCT_SEARCH_URL,
                        RequestOptions.create()
                                .setHeader("Content-Type", "application/json")
                                .setData(body));
                if (!response.ok()) {
                    log.warn("product/_search (buyout) HTTP {} для {} sku", response.status(), batch.size());
                    continue;
                }
                JsonNode data = MAPPER.readTree(response.text());
                for (JsonNode hit : data.path("hits").path("hits")) {
                    JsonNode source = hit.get("_source");
                    if (source == null || !source.isObject()) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> sourceMap = MAPPER.convertValue(source, Map.class);
                    String sku = source.path("sku").asText(null);
                    Double buyout = RshbPageParser.buyoutPriceFromProductSource(sourceMap);
                    if (sku != null && buyout != null) {
                        registry.put(sku, buyout);
                    }
                }
            } catch (Exception e) {
                log.warn("product/_search (buyout): {}", e.getMessage());
            }
        }

        if (!registry.isEmpty()) {
            log.info("buy_price из product/_search: {} sku", registry.size());
        }
        return registry;
    }

    private static String resolveRegionCode(String region, boolean investmentOnly) {
        if (region != null && !region.isBlank()) {
            return region.strip();
        }
        return investmentOnly
                ? RshbPageParser.DEFAULT_REGION_CODE
                : RshbPageParser.IN_STOCK_CATALOG_REGION_CODE;
    }

    private Browser launchBrowser(Playwright playwright) {
        return browserLauncher.launch(playwright, headless, LAUNCH_ARGS);
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }

    public record PageLoadRequest(int pageNum, String searchText, boolean investmentOnly) {}
}
