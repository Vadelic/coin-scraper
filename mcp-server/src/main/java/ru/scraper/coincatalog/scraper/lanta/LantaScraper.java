package ru.scraper.coincatalog.scraper.lanta;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.PlaywrightBrowserLauncher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;

/** Скрапер каталога Lanta через Playwright. */
@Slf4j
@Service("LANTA")
public class LantaScraper implements CoinScraper<Coin> {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final List<String> LAUNCH_ARGS = List.of(
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--disable-blink-features=AutomationControlled");
    private static final List<String> IGNORE_DEFAULT_ARGS = List.of("--enable-automation");
    private static final Set<String> BLOCKED_RESOURCE_TYPES = Set.of("image", "media", "font");
    private static final String STEALTH_INIT_SCRIPT =
            "Object.defineProperty(navigator, 'webdriver', { get: () => undefined });";
    private static final String CAPTCHA_HINT =
            "Пройдите CAPTCHA и сохраните сессию: tools/coin-catalog-lanta/save_lanta_session.sh "
                    + "или задайте LANTA_STORAGE_STATE (файл также ищется в mcp-server/data/lanta-storage-state.json).";
    private static final Path DEFAULT_STORAGE =
            Path.of(System.getProperty("user.dir"), "data", "lanta-storage-state.json");
    private static final String WARM_UP_URL = "https://www.lanta.ru/";

    private static final String SEARCH_INPUT_SELECTOR = "input[name=\"keywords\"]";
    private static final int DEFAULT_SCROLL_PASSES = 8;

    private final PlaywrightBrowserLauncher browserLauncher;
    private final boolean headless;
    private final Duration timeout;
    private final int retries;
    private final double delaySeconds;
    private final int scrollPasses;
    private final BiFunction<String, String, String> popupLoaderOverride;

    public LantaScraper() {
        this(new PlaywrightBrowserLauncher(), null);
    }

    @Autowired
    public LantaScraper(PlaywrightBrowserLauncher browserLauncher) {
        this(browserLauncher, null);
    }

    LantaScraper(BiFunction<String, String, String> popupLoaderOverride) {
        this(new PlaywrightBrowserLauncher(), popupLoaderOverride);
    }

    LantaScraper(
            PlaywrightBrowserLauncher browserLauncher,
            BiFunction<String, String, String> popupLoaderOverride) {
        this(browserLauncher, true, Duration.ofMillis(60_000), 3, 0.5, DEFAULT_SCROLL_PASSES, popupLoaderOverride);
    }

    LantaScraper(
            PlaywrightBrowserLauncher browserLauncher,
            boolean headless,
            Duration timeout,
            int retries,
            double delaySeconds,
            int scrollPasses,
            BiFunction<String, String, String> popupLoaderOverride) {
        this.browserLauncher = browserLauncher;
        this.headless = headless;
        this.timeout = timeout;
        this.retries = Math.max(1, retries);
        this.delaySeconds = Math.max(0, delaySeconds);
        this.scrollPasses = Math.max(1, scrollPasses);
        this.popupLoaderOverride = popupLoaderOverride;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String catalogUrl = LantaPageParser.resolveCatalogUrl(investmentOnly);
        String normalizedQuery = query != null ? query.strip() : "";

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            try (browser) {
                BrowserContext context = createContext(browser);
                boolean hasSession = resolvedStorageStatePath().isPresent();
                Page page = context.newPage();
                page.setDefaultTimeout(timeout.toMillis());

                if (hasSession) {
                    warmUpSession(page);
                }
                if (!navigateWithRetries(page, catalogUrl)) {
                    return new ScrapePayload(0, List.of());
                }

                page.waitForTimeout(3000);
                if (isCaptcha(page)) {
                    log.warn("CAPTCHA после первого захода — повтор через прогрев");
                    warmUpSession(page);
                    if (!navigateWithRetries(page, catalogUrl)) {
                        return new ScrapePayload(0, List.of());
                    }
                    page.waitForTimeout(3000);
                }
                if (isCaptcha(page)) {
                    throw new CaptchaBlockedException(
                            "lanta.ru показал CAPTCHA («Вы точно не робот?») вместо каталога. " + CAPTCHA_HINT);
                }

                if (!normalizedQuery.isEmpty()) {
                    applyCatalogSearch(page, normalizedQuery);
                }

                scrollUntilStable(page);
                List<LantaPageParser.ListItem> listItems = collectListItems(page);
                log.info("Найдено карточек в каталоге: {}", listItems.size());

                List<Coin> coins = enrichCoins(page, listItems);
                saveStorageState(context);
                return new ScrapePayload(1, coins);
            }
        }
    }

    private BrowserContext createContext(Browser browser) {
        Browser.NewContextOptions options = new Browser.NewContextOptions()
                .setUserAgent(USER_AGENT)
                .setLocale("ru-RU")
                .setTimezoneId("Europe/Moscow")
                .setViewportSize(1366, 900)
                .setExtraHTTPHeaders(Map.of(
                        "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7"));

        Optional<Path> storageState = resolvedStorageStatePath();
        storageState.ifPresent(path -> {
            options.setStorageStatePath(path);
            log.info("Загружаю сессию Lanta из {}", path);
        });

        BrowserContext context = browser.newContext(options);
        context.addInitScript(STEALTH_INIT_SCRIPT);
        context.route("**/*", route -> {
            if (BLOCKED_RESOURCE_TYPES.contains(route.request().resourceType())) {
                route.abort();
            } else {
                route.resume();
            }
        });
        return context;
    }

    private void warmUpSession(Page page) {
        try {
            log.info("Прогрев сессии: {}", WARM_UP_URL);
            page.navigate(
                    WARM_UP_URL,
                    new Page.NavigateOptions()
                            .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                            .setTimeout(timeout.toMillis()));
            page.waitForTimeout(2000);
        } catch (Exception e) {
            log.warn("Прогрев сессии не удался: {}", e.getMessage());
        }
    }

    private void saveStorageState(BrowserContext context) {
        String env = System.getenv("LANTA_STORAGE_STATE");
        if (resolvedStorageStatePath().isEmpty() && (env == null || env.isBlank())) {
            return;
        }
        Optional<Path> savePath = resolvedSaveStorageStatePath();
        if (savePath.isEmpty()) {
            return;
        }
        Path path = savePath.get();
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            context.storageState(new BrowserContext.StorageStateOptions().setPath(path));
            log.info("Сессия Lanta сохранена: {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Не удалось сохранить сессию Lanta ({}): {}", path, e.getMessage());
        }
    }

    private static Optional<Path> resolvedStorageStatePath() {
        String env = System.getenv("LANTA_STORAGE_STATE");
        if (env != null && !env.isBlank()) {
            Optional<Path> configured = pathIfReadable(env.strip());
            if (configured.isPresent()) {
                return configured;
            }
        }
        return pathIfReadable(DEFAULT_STORAGE.toString());
    }

    private static Optional<Path> resolvedSaveStorageStatePath() {
        String env = System.getenv("LANTA_STORAGE_STATE");
        if (env != null && !env.isBlank()) {
            return Optional.of(Path.of(env.strip()));
        }
        return Optional.of(DEFAULT_STORAGE);
    }

    private static Optional<Path> pathIfReadable(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Path path = Path.of(raw.strip());
        return Files.isRegularFile(path) ? Optional.of(path) : Optional.empty();
    }

    private List<Coin> enrichCoins(Page page, List<LantaPageParser.ListItem> listItems) {
        List<LantaPageParser.CoinCandidate> candidates = new ArrayList<>();

        for (LantaPageParser.ListItem item : listItems) {
            String catalogNumber = null;
            String popupHtml = null;
            LantaPageParser.ListItem pricedItem = item;
            if (item.id() != null && !item.id().isBlank()) {
                try {
                    popupHtml = loadPopupHtml(page, item.id(), item.formId());
                    catalogNumber = LantaPageParser.parseArticleFromPopupHtml(popupHtml);
                    pricedItem = refreshListPrices(page, item);
                } catch (Exception e) {
                    log.warn("Попап id={}: {}", item.id(), e.getMessage());
                }
                if (delaySeconds > 0) {
                    sleep(delaySeconds);
                }
            }

            var coinOpt = LantaPageParser.listItemToCoin(pricedItem, catalogNumber, popupHtml);
            if (coinOpt.isEmpty()) {
                log.warn("Пропуск карточки: пустое название, id={}", item.id());
                continue;
            }
            candidates.add(new LantaPageParser.CoinCandidate(item, coinOpt.get()));
        }

        List<Coin> coins = new ArrayList<>();
        Set<String> seenKeys = new HashSet<>();
        for (LantaPageParser.CoinCandidate candidate : LantaPageParser.collapseCatalogVariants(candidates)) {
            Coin coin = candidate.coin();
            String key = LantaPageParser.dedupeKey(candidate.item(), coin);
            if (!seenKeys.add(key)) {
                log.warn(
                        "Дубликат карточки (ключ {}): id={}, «{}», артикул={}, вес={} г",
                        key,
                        candidate.item().id(),
                        coin.name(),
                        coin.catalogNumber(),
                        coin.weightG());
                continue;
            }
            if (coin.buyPrice() == null && coin.sellPrice() == null) {
                log.warn("Нет цен для «{}» (артикул {})", coin.name(), coin.catalogNumber());
            }
            coins.add(coin);
        }
        return coins;
    }

    private String loadPopupHtml(Page page, String coinId, String formId) {
        String form = formId != null && !formId.isBlank() ? formId : "892";
        if (popupLoaderOverride != null) {
            return popupLoaderOverride.apply(coinId, form);
        }
        return (String) page.evaluate(
                LantaPageParser.fetchPopupJs(),
                Map.of("path", LantaPageParser.POPUP_PATH, "id", coinId, "formId", form));
    }

    private boolean navigateWithRetries(Page page, String url) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                log.info("Открываю {} (попытка {}/{})", url, attempt, retries);
                page.navigate(
                        url,
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED)
                                .setTimeout(timeout.toMillis()));
                return true;
            } catch (Exception e) {
                lastError = e;
                log.warn("Навигация: попытка {}/{} — {}", attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(Math.pow(2, attempt));
                }
            }
        }
        log.error("Не удалось открыть страницу каталога: {}", lastError != null ? lastError.getMessage() : "unknown");
        return false;
    }

    private boolean isCaptcha(Page page) {
        return LantaPageParser.isCaptchaTitle(page.title())
                || LantaPageParser.isCaptchaBody(snippetBody(page));
    }

    private void applyCatalogSearch(Page page, String query) {
        log.info("Поиск по запросу: «{}»", query);
        Locator searchInput = page.locator(SEARCH_INPUT_SELECTOR);
        searchInput.waitFor(new Locator.WaitForOptions().setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));
        searchInput.fill("");
        searchInput.fill(query);

        Locator form = searchInput.locator("xpath=ancestor::form[1]");
        Locator submit = form.locator("button[type=\"submit\"]").first();
        if (submit.count() > 0) {
            submit.click(new Locator.ClickOptions().setForce(true));
        } else {
            searchInput.press("Enter");
        }

        page.waitForTimeout(2000);
        page.waitForSelector(LantaPageParser.COIN_LIST_SELECTOR);

        int prevCount = -1;
        for (int i = 0; i < 4; i++) {
            int count = page.locator(LantaPageParser.COIN_ITEM_SELECTOR).count();
            if (count == prevCount) {
                break;
            }
            prevCount = count;
            sleep(0.5);
        }
    }

    private void scrollUntilStable(Page page) {
        String jsCount =
                "() => document.querySelectorAll(" + jsonString(LantaPageParser.COIN_ITEM_SELECTOR) + ").length";

        int stable = 0;
        int prev = -1;
        for (int i = 0; i < scrollPasses; i++) {
            page.mouse().wheel(0, 3000);
            sleep(delaySeconds);
            int cur;
            try {
                cur = ((Number) page.evaluate(jsCount)).intValue();
            } catch (PlaywrightException e) {
                log.debug("scrollUntilStable: evaluate пропущен ({})", e.getMessage());
                continue;
            }
            if (cur == prev) {
                stable++;
            } else {
                stable = 0;
            }
            prev = cur;
            if (stable >= 2) {
                break;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private LantaPageParser.ListItem refreshListPrices(Page page, LantaPageParser.ListItem item) {
        if (item.id() == null || item.id().isBlank()) {
            return item;
        }
        try {
            Map<String, Object> row =
                    (Map<String, Object>) page.evaluate(LantaPageParser.readListItemPricesJs(), item.id());
            if (row == null) {
                return item;
            }
            return new LantaPageParser.ListItem(
                    item.id(),
                    item.formId(),
                    item.name(),
                    stringOrNull(row.get("sellRaw")),
                    stringOrNull(row.get("buyRaw")),
                    Boolean.TRUE.equals(row.get("out")),
                    item.info());
        } catch (PlaywrightException e) {
            log.debug("refreshListPrices id={}: {}", item.id(), e.getMessage());
            return item;
        }
    }

    @SuppressWarnings("unchecked")
    private List<LantaPageParser.ListItem> collectListItems(Page page) {
        page.waitForSelector(LantaPageParser.COIN_LIST_SELECTOR);
        List<Map<String, Object>> rows =
                (List<Map<String, Object>>) page.evaluate(LantaPageParser.collectListJs());
        List<LantaPageParser.ListItem> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            if (row == null) {
                continue;
            }
            items.add(new LantaPageParser.ListItem(
                    stringOrNull(row.get("id")),
                    stringOrNull(row.get("formId")),
                    stringOrNull(row.get("name")),
                    stringOrNull(row.get("sellRaw")),
                    stringOrNull(row.get("buyRaw")),
                    Boolean.TRUE.equals(row.get("out")),
                    infoLines(row.get("info"))));
        }
        return items;
    }

    @SuppressWarnings("unchecked")
    private static List<String> infoLines(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                String line = item.toString().strip();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text;
    }

    private static String snippetBody(Page page) {
        try {
            String body = page.locator("body")
                    .innerText(new Locator.InnerTextOptions().setTimeout(5000));
            return body.length() > 4000 ? body.substring(0, 4000) : body;
        } catch (PlaywrightException e) {
            return "";
        }
    }

    private Browser launchBrowser(Playwright playwright) {
        return browserLauncher.launch(playwright, headless, LAUNCH_ARGS, IGNORE_DEFAULT_ARGS);
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }

    private static String jsonString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
