package ru.scraper.coincatalog.scraper.aurumex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.CoinScraper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.RequestOptions;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.PlaywrightBrowserLauncher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Скрапер каталога Aurumex через Playwright и JSON payload. */
@Slf4j
@Service("AURUMEX")
public class AurumexScraper implements CoinScraper<Coin> {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final List<String> LAUNCH_ARGS = List.of("--no-sandbox", "--disable-setuid-sandbox");

    private final PlaywrightBrowserLauncher browserLauncher;
    private final ObjectMapper objectMapper;
    private final boolean headless;
    private final Duration timeout;
    private final int retries;
    private final double delaySeconds;
    private final int maxPages;

    public AurumexScraper() {
        this(new PlaywrightBrowserLauncher());
    }

    @Autowired
    public AurumexScraper(PlaywrightBrowserLauncher browserLauncher) {
        this(browserLauncher, true, Duration.ofMillis(60_000), 3, 0.5, AurumexPayloadParser.DEFAULT_MAX_PAGES);
    }

    AurumexScraper(
            PlaywrightBrowserLauncher browserLauncher,
            boolean headless,
            Duration timeout,
            int retries,
            double delaySeconds,
            int maxPages) {
        this.browserLauncher = browserLauncher;
        this.objectMapper = new ObjectMapper();
        this.headless = headless;
        this.timeout = timeout;
        this.retries = Math.max(1, retries);
        this.delaySeconds = Math.max(0, delaySeconds);
        this.maxPages = Math.max(1, maxPages);
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";
        List<JsonNode> stores = new ArrayList<>();
        int pagesProcessed = 0;
        Map<String, Boolean> seenKeys = new LinkedHashMap<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            try (browser) {
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(USER_AGENT)
                        .setIgnoreHTTPSErrors(true));
                Page page = context.newPage();
                page.setDefaultTimeout(timeout.toMillis());
                log.info("Открываю каталог: {}", AurumexPayloadParser.CATALOG_URL);
                page.navigate(
                        AurumexPayloadParser.CATALOG_URL,
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));

                if (isCaptcha(page)) {
                    throw new CaptchaBlockedException("CAPTCHA на странице каталога Aurumex");
                }

                APIRequestContext request = context.request();

                for (int pageNum = 1; pageNum <= maxPages; pageNum++) {
                    String url = AurumexPayloadParser.payloadUrlForPage(pageNum);
                    JsonNode store = fetchPayloadJson(request, url);
                    if (!store.isArray()) {
                        throw new IllegalStateException("Неверный формат payload: " + url);
                    }

                    var pageCoins = AurumexPayloadParser.extractCoinsFromStore(store);
                    pagesProcessed++;
                    int before = seenKeys.size();

                    for (var coin : pageCoins) {
                        String key = AurumexPayloadParser.dedupeKey(coin);
                        if (seenKeys.containsKey(key)) {
                            log.warn("Пропуск дубликата: {}", key);
                            continue;
                        }
                        seenKeys.put(key, Boolean.TRUE);
                    }

                    stores.add(store);
                    int added = seenKeys.size() - before;
                    log.info(
                            "Страница {}: в payload={}, добавлено={}, всего={}",
                            pageNum,
                            pageCoins.size(),
                            added,
                            seenKeys.size());

                    if (added == 0 && !seenKeys.isEmpty()) {
                        log.info("Остановка: новые монеты на странице {} не добавились", pageNum);
                        break;
                    }
                }
            }
        }

        List<Coin> coins = mergeAndFilter(stores, normalizedQuery);
        if (!normalizedQuery.isEmpty()) {
            log.info("После фильтра query=«{}»: {} монет", normalizedQuery, coins.size());
        }
        return new ScrapePayload(pagesProcessed, coins);
    }

    List<Coin> mergeAndFilter(List<JsonNode> stores, String query) {
        Map<String, Coin> byKey = new LinkedHashMap<>();
        for (JsonNode store : stores) {
            for (Coin coin : AurumexPayloadParser.extractCoinsFromStore(store)) {
                String key = AurumexPayloadParser.dedupeKey(coin);
                if (byKey.containsKey(key)) {
                    continue;
                }
                if (!AurumexPayloadParser.coinMatchesQuery(coin, query)) {
                    continue;
                }
                if (coin.sellPrice() == null) {
                    log.warn(
                            "Нет sell_price для «{}» (артикул {})",
                            coin.name(),
                            coin.catalogNumber());
                }
                byKey.put(key, coin);
            }
        }
        return new ArrayList<>(byKey.values());
    }

    private JsonNode fetchPayloadJson(APIRequestContext request, String url) {
        Exception lastError = null;
        RequestOptions options = RequestOptions.create()
                .setTimeout(timeout.toMillis())
                .setHeader("Accept", "application/json, text/plain, */*")
                .setHeader("Referer", AurumexPayloadParser.CATALOG_URL);

        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                log.info("Загрузка payload: {} (попытка {}/{})", url, attempt, retries);
                APIResponse response = request.get(url, options);
                if (!response.ok()) {
                    throw new IllegalStateException("HTTP " + response.status() + " для " + url);
                }
                return objectMapper.readTree(response.text());
            } catch (Exception e) {
                lastError = e;
                log.warn("Payload {}: попытка {}/{} — {}", url, attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(delaySeconds * Math.pow(2, attempt - 1));
                }
            }
        }
        throw new IllegalStateException("Не удалось загрузить " + url + ": " + lastError);
    }

    private boolean isCaptcha(Page page) {
        if (AurumexPayloadParser.isCaptchaTitle(page.title())) {
            return true;
        }
        try {
            String body = page.locator("body")
                    .innerText(new com.microsoft.playwright.Locator.InnerTextOptions().setTimeout(5000));
            String snippet = body.length() > 4000 ? body.substring(0, 4000) : body;
            return AurumexPayloadParser.isCaptchaBody(snippet);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private Browser launchBrowser(Playwright playwright) {
        return browserLauncher.launch(playwright, headless, LAUNCH_ARGS);
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }
}
