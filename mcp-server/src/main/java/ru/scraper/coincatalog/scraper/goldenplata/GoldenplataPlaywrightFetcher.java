package ru.scraper.coincatalog.scraper.goldenplata;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.scraper.CaptchaBlockedException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

@Service
public class GoldenplataPlaywrightFetcher {

    private static final Logger log = LoggerFactory.getLogger(GoldenplataPlaywrightFetcher.class);

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final List<String> BROWSER_CHANNELS = List.of("chrome", "msedge", "chromium");
    private static final List<String> LAUNCH_ARGS = List.of("--no-sandbox", "--disable-setuid-sandbox");
    private static final Set<String> BLOCKED_RESOURCE_TYPES = Set.of("image", "media", "font");

    private final boolean headless;
    private final Duration timeout;
    private final int retries;
    private final double delaySeconds;
    private final int maxPages;
    private final Function<PageFetchRequest, String> pageHtmlOverride;

    public GoldenplataPlaywrightFetcher() {
        this(null);
    }

    GoldenplataPlaywrightFetcher(Function<PageFetchRequest, String> pageHtmlOverride) {
        this(true, Duration.ofMillis(60_000), 3, 0.4, 0, pageHtmlOverride);
    }

    GoldenplataPlaywrightFetcher(
            boolean headless,
            Duration timeout,
            int retries,
            double delaySeconds,
            int maxPages,
            Function<PageFetchRequest, String> pageHtmlOverride) {
        this.headless = headless;
        this.timeout = timeout;
        this.retries = Math.max(1, retries);
        this.delaySeconds = Math.max(0, delaySeconds);
        this.maxPages = maxPages;
        this.pageHtmlOverride = pageHtmlOverride;
    }

    public FetchResult fetchCatalog(String query, boolean investmentOnly) {
        String catalogBase = GoldenplataPageParser.resolveCatalogBase(investmentOnly);
        String normalizedQuery = query != null ? query.strip() : "";
        List<String> htmlPages = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            try (browser) {
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(USER_AGENT)
                        .setLocale("ru-RU")
                        .setViewportSize(1366, 900)
                        .setExtraHTTPHeaders(Map.of(
                                "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")));
                context.route("**/*", route -> {
                    if (BLOCKED_RESOURCE_TYPES.contains(route.request().resourceType())) {
                        route.abort();
                    } else {
                        route.resume();
                    }
                });

                Page page = context.newPage();
                page.setDefaultTimeout(timeout.toMillis());

                String firstUrl = GoldenplataPageParser.buildCatalogUrl(catalogBase, 1, normalizedQuery);
                String firstHtml = fetchPageHtml(page, firstUrl, 1);
                if (GoldenplataPageParser.isCaptchaTitle(page.title())
                        || GoldenplataPageParser.isCaptchaBody(snippetBody(page))) {
                    throw new CaptchaBlockedException(
                            "goldenplata.ru показал CAPTCHA вместо каталога. "
                                    + "Попробуйте headful-режим и пройдите проверку вручную.");
                }

                int pagesToVisit = GoldenplataPageParser.parseTotalPages(firstHtml);
                if (maxPages > 0) {
                    pagesToVisit = Math.min(pagesToVisit, maxPages);
                }
                log.info("Страниц к обходу: {}", pagesToVisit);

                for (int pageNum = 1; pageNum <= pagesToVisit; pageNum++) {
                    String html = pageNum == 1
                            ? firstHtml
                            : fetchPageHtml(
                                    page,
                                    GoldenplataPageParser.buildCatalogUrl(catalogBase, pageNum, normalizedQuery),
                                    pageNum);
                    htmlPages.add(html);
                    log.info(
                            "Страница {}: найдено {}",
                            pageNum,
                            GoldenplataPageParser.parseCoinsFromHtml(html).size());
                    if (pageNum < pagesToVisit && delaySeconds > 0) {
                        sleep(delaySeconds);
                    }
                }
            }
        }

        return new FetchResult(htmlPages.size(), htmlPages);
    }

    private String fetchPageHtml(Page page, String url, int pageNum) {
        if (pageHtmlOverride != null) {
            return pageHtmlOverride.apply(new PageFetchRequest(url, pageNum));
        }

        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                log.info("Открываю {} (попытка {}/{})", url, attempt, retries);
                page.navigate(
                        url,
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(2000);
                dismissCookieBanner(page);
                String content = page.content();
                if (GoldenplataPageParser.hasAnalyticsPayload(content)
                        || GoldenplataPageParser.isCaptchaTitle(page.title())
                        || GoldenplataPageParser.isCaptchaBody(snippetBody(page))) {
                    return content;
                }
                page.waitForTimeout(1500);
                return page.content();
            } catch (Exception e) {
                lastError = e;
                log.warn("Навигация: попытка {}/{} — {}", attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(Math.pow(2, attempt));
                }
            }
        }
        throw new IllegalStateException("Не удалось загрузить " + url + ": " + lastError);
    }

    private static void dismissCookieBanner(Page page) {
        for (String selector : List.of(
                "button:has-text(\"ОК\")", "button:has-text(\"OK\")", ".cookie-alert button")) {
            Locator btn = page.locator(selector).first();
            if (btn.count() > 0) {
                try {
                    btn.click(new Locator.ClickOptions().setTimeout(2000));
                    page.waitForTimeout(500);
                    return;
                } catch (PlaywrightException ignored) {
                    // try next selector
                }
            }
        }
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
                "Не найден браузер для Playwright.\n" + String.join("\n", errors));
    }

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }

    public record PageFetchRequest(String url, int pageNum) {}

    public record FetchResult(int pagesProcessed, List<String> pageHtmls) {}
}
