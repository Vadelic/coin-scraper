package ru.scraper.coincatalog.scraper.vtb;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.common.ScrapePayload;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Скрапер каталога ВТБ через Playwright и BFF API. */
@Slf4j
@Service("VTB")
public class VtbScraper implements CoinScraper<Coin> {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final List<String> BROWSER_CHANNELS = List.of("chrome", "msedge", "chromium");
    private static final List<String> LAUNCH_ARGS = List.of("--no-sandbox", "--disable-setuid-sandbox");

    private static final String FETCH_JS =
            """
            async (args) => {
                const r = await fetch(args.url, {
                    method: 'POST',
                    headers: {
                        'Content-Type': 'application/json',
                        'Accept': 'application/json',
                    },
                    body: JSON.stringify(args.body),
                    credentials: 'include',
                });
                const ct = r.headers.get('content-type') || '';
                if (!r.ok) {
                    const text = await r.text();
                    throw new Error('HTTP ' + r.status + ': ' + text.slice(0, 200));
                }
                if (!ct.includes('application/json')) {
                    const text = await r.text();
                    throw new Error('Non-JSON (' + ct + '): ' + text.slice(0, 200));
                }
                return await r.json();
            }
            """;

    private final ObjectMapper objectMapper;
    private final boolean headless;
    private final Duration timeout;
    private final int retries;
    private final double delaySeconds;
    private final Integer maxPages;

    public VtbScraper() {
        this(true, Duration.ofMillis(45_000), 3, 0.35, null);
    }

    VtbScraper(
            boolean headless, Duration timeout, int retries, double delaySeconds, Integer maxPages) {
        this.objectMapper = new ObjectMapper();
        this.headless = headless;
        this.timeout = timeout;
        this.retries = Math.max(1, retries);
        this.delaySeconds = delaySeconds;
        this.maxPages = maxPages;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String normalizedQuery = query != null ? query.strip() : "";
        if (!normalizedQuery.isBlank()) {
            log.info("Фильтр query=«{}» (локально по name / catalog_number / metal)", normalizedQuery);
        }
        List<ObjectNode> apiFilters = VtbBffResponseParser.resolveApiFilters(investmentOnly);
        List<JsonNode> allRows = new ArrayList<>();
        int pagesProcessed = 0;

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            try (browser) {
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(USER_AGENT)
                        .setLocale("ru-RU")
                        .setViewportSize(1280, 800)
                        .setExtraHTTPHeaders(Map.of(
                                "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")));
                Page page = context.newPage();
                page.setDefaultTimeout(timeout.toMillis());

                log.info("Открываю {}", VtbBffResponseParser.COIN_CATALOG_URL);
                page.navigate(
                        VtbBffResponseParser.COIN_CATALOG_URL,
                        new Page.NavigateOptions().setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));

                if (isCaptcha(page)) {
                    throw new CaptchaBlockedException(
                            "ВТБ показал страницу проверки вместо каталога. "
                                    + "Попробуйте headful-режим и пройдите проверку вручную.");
                }

                if (investmentOnly) {
                    log.info(
                            "Фильтр BFF: {}={}",
                            VtbBffResponseParser.COIN_KIND_FILTER_ID,
                            VtbBffResponseParser.INVESTMENT_KIND_VALUE);
                }

                int pageNum = 1;
                int maxPageSeen = 1;
                ObjectNode payload = VtbBffResponseParser.buildPayload(apiFilters);

                while (true) {
                    if (pageNum > 1 && delaySeconds > 0) {
                        sleep(delaySeconds);
                    }

                    log.info("Запрос списка page={}", pageNum);
                    JsonNode body = fetchListPage(page, pageNum, payload);
                    if (body == null) {
                        log.error("Страница {}: пустой ответ BFF — останов", pageNum);
                        break;
                    }

                    var parsed = VtbBffResponseParser.parseListResponse(body);
                    maxPageSeen = Math.max(maxPageSeen, parsed.maxPage());
                    pagesProcessed++;
                    allRows.addAll(parsed.coins());

                    log.info(
                            "Страница {}/{}: записей {} (всего строк: {})",
                            pageNum,
                            maxPageSeen,
                            parsed.coins().size(),
                            allRows.size());

                    if (maxPages != null && pageNum >= maxPages) {
                        break;
                    }
                    if (pageNum >= maxPageSeen) {
                        break;
                    }
                    pageNum++;
                }
            }
        }

        return new ScrapePayload(pagesProcessed, toCoins(allRows, normalizedQuery));
    }

    List<Coin> toCoins(List<JsonNode> rows, String query) {
        List<Coin> coins = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode row : rows) {
            var coinOpt = VtbBffResponseParser.rowToCoin(row);
            if (coinOpt.isEmpty()) {
                log.warn("Пропуск записи: нет name и article");
                continue;
            }
            Coin coin = coinOpt.get();
            if (coin.name() == null || coin.name().isBlank()) {
                log.warn("Пропуск записи: пустое name, article={}", coin.catalogNumber());
                continue;
            }
            if (!VtbBffResponseParser.coinMatchesQuery(coin, query)) {
                continue;
            }
            String key = VtbBffResponseParser.dedupeKey(row, coin);
            if (key == null || key.isBlank()) {
                continue;
            }
            if (!seen.add(key)) {
                log.warn(
                        "Дубликат (id {}): «{}», артикул={}",
                        key,
                        coin.name(),
                        coin.catalogNumber());
                continue;
            }
            if (coin.sellPrice() == null) {
                log.warn(
                        "Нет sell_price для «{}» (артикул {})",
                        coin.name(),
                        coin.catalogNumber());
            }
            coins.add(coin);
        }
        return coins;
    }

    private JsonNode fetchListPage(Page page, int pageNum, ObjectNode payload) {
        String url = VtbBffResponseParser.buildListUrl(pageNum);
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("url", url);
        args.put("body", objectMapper.convertValue(payload, Map.class));

        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                Object raw = page.evaluate(FETCH_JS, args);
                return objectMapper.valueToTree(raw);
            } catch (Exception e) {
                lastError = e;
                log.warn("Страница {}, попытка {}/{} — {}", pageNum, attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(Math.pow(2, attempt));
                }
            }
        }
        log.error("Страница {}: все попытки исчерпаны: {}", pageNum, lastError != null ? lastError.getMessage() : "");
        return null;
    }

    private boolean isCaptcha(Page page) {
        if (VtbBffResponseParser.isCaptchaTitle(page.title())) {
            return true;
        }
        try {
            String body = page.locator("body").innerText(new com.microsoft.playwright.Locator.InnerTextOptions().setTimeout(5000));
            return VtbBffResponseParser.isCaptchaBody(body);
        } catch (PlaywrightException e) {
            return false;
        }
    }

    private Browser launchBrowser(Playwright playwright) {
        List<String> errors = new ArrayList<>();
        for (String channel : BROWSER_CHANNELS) {
            try {
                Browser browser = playwright.chromium()
                        .launch(new BrowserType.LaunchOptions()
                                .setHeadless(headless)
                                .setChannel(channel)
                                .setArgs(LAUNCH_ARGS));
                log.info("Браузер: {}", channel);
                return browser;
            } catch (PlaywrightException e) {
                errors.add(channel + ": " + e.getMessage());
            }
        }
        try {
            Browser browser = playwright.chromium()
                    .launch(new BrowserType.LaunchOptions().setHeadless(headless).setArgs(LAUNCH_ARGS));
            log.info("Браузер: playwright bundled chromium");
            return browser;
        } catch (PlaywrightException e) {
            errors.add("bundled: " + e.getMessage());
        }
        throw new IllegalStateException(
                "Не найден браузер для Playwright. Установите Google Chrome или Microsoft Edge.\n"
                        + String.join("\n", errors));
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
