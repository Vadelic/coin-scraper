package ru.scraper.coincatalog.scraper.atb;

import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.RequestOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.PlaywrightBrowserLauncher;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Скрапер каталога АТБ через Playwright и AJAX-фрагменты. */
@Slf4j
@Service("ATB")
public class AtbScraper implements CoinScraper<Coin> {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                    + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private static final List<String> LAUNCH_ARGS = List.of("--no-sandbox", "--disable-setuid-sandbox");

    private final PlaywrightBrowserLauncher browserLauncher;
    private final boolean headless;
    private final Duration timeout;
    private final int retries;
    private final double delaySeconds;
    private final Function<String, String> detailLoaderOverride;

    public AtbScraper() {
        this(new PlaywrightBrowserLauncher(), null);
    }

    @Autowired
    public AtbScraper(PlaywrightBrowserLauncher browserLauncher) {
        this(browserLauncher, null);
    }

    AtbScraper(Function<String, String> detailLoaderOverride) {
        this(new PlaywrightBrowserLauncher(), detailLoaderOverride);
    }

    AtbScraper(PlaywrightBrowserLauncher browserLauncher, Function<String, String> detailLoaderOverride) {
        this(browserLauncher, true, Duration.ofMillis(30_000), 3, 0.5, detailLoaderOverride);
    }

    AtbScraper(
            PlaywrightBrowserLauncher browserLauncher,
            boolean headless,
            Duration timeout,
            int retries,
            double delaySeconds,
            Function<String, String> detailLoaderOverride) {
        this.browserLauncher = browserLauncher;
        this.headless = headless;
        this.timeout = timeout;
        this.retries = Math.max(1, retries);
        this.delaySeconds = Math.max(0, delaySeconds);
        this.detailLoaderOverride = detailLoaderOverride;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String category = AtbPageParser.resolveCategory(investmentOnly);
        String normalizedQuery = query != null ? query.strip() : "";

        try (Playwright playwright = Playwright.create()) {
            Browser browser = launchBrowser(playwright);
            try (browser) {
                BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                        .setUserAgent(USER_AGENT)
                        .setIgnoreHTTPSErrors(true));
                Page page = context.newPage();
                page.setDefaultTimeout(timeout.toMillis());

                log.info("Открываю каталог: {}", AtbPageParser.CATALOG_URL);
                page.navigate(
                        AtbPageParser.CATALOG_URL,
                        new Page.NavigateOptions()
                                .setWaitUntil(com.microsoft.playwright.options.WaitUntilState.DOMCONTENTLOADED));

                String mainHtml = page.content();
                if (AtbPageParser.detectCaptcha(mainHtml)) {
                    throw new CaptchaBlockedException("CAPTCHA на странице каталога ATB");
                }

                APIRequestContext request = context.request();
                String fragment = fetchAjaxFragment(request, category, normalizedQuery);
                List<Coin> coins = parseCoinsFromFragment(request, fragment);
                return buildResult(fragment, coins);
            }
        }
    }

    static ScrapePayload<Coin> buildResult(String fragment, List<Coin> coins) {
        if (coins.isEmpty()) {
            if (AtbPageParser.isEmptySearchResult(fragment)) {
                return new ScrapePayload(1, List.of());
            }
            throw new IllegalStateException("Не удалось распарсить монеты из ответа");
        }
        return new ScrapePayload(1, coins);
    }

    private List<Coin> parseCoinsFromFragment(APIRequestContext request, String fragmentHtml) {
        List<AtbPageParser.CardMatch> cards = AtbPageParser.parseCardsFromFragment(fragmentHtml);
        log.info("Карточек в ответе: {}", cards.size());

        List<Coin> coins = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        int index = 0;
        for (AtbPageParser.CardMatch card : cards) {
            index++;
            String detailHtml = loadDetailHtml(request, AtbPageParser.BASE_URL, card.href());
            var parsed = AtbPageParser.parseCard(card.cardHtml(), card.href(), detailHtml);
            if (parsed.isEmpty()) {
                log.warn("Пропуск карточки: пустое имя");
                continue;
            }
            AtbPageParser.ParsedCard parsedCard = parsed.get();
            if (!seenUrls.add(parsedCard.url())) {
                log.warn("Пропуск дубликата: {}", parsedCard.url());
                continue;
            }
            if (parsedCard.coin().sellPrice() == null) {
                log.warn("Нет цены на карточке: {}", parsedCard.coin().name());
            }
            coins.add(parsedCard.coin());
            if (index % 20 == 0) {
                log.info("Обработано карточек: {}/{}", index, cards.size());
            }
        }
        return coins;
    }

    private String loadDetailHtml(APIRequestContext request, String baseUrl, String href) {
        if (detailLoaderOverride != null) {
            String url = href.startsWith("http") ? href : baseUrl + href;
            return detailLoaderOverride.apply(url);
        }
        String url = href.startsWith("http") ? href : baseUrl + href;
        return requestWithRetry(
                request,
                "GET",
                url,
                RequestOptions.create()
                        .setTimeout(timeout.toMillis())
                        .setHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .setHeader("Referer", AtbPageParser.CATALOG_URL));
    }

    private String fetchAjaxFragment(APIRequestContext request, String category, String query) {
        String body = AtbPageParser.buildRequestBody(category, query);
        return requestWithRetry(
                request,
                "POST",
                AtbPageParser.CATALOG_URL,
                RequestOptions.create()
                        .setTimeout(timeout.toMillis())
                        .setHeader("Accept", "text/html, */*;q=0.1")
                        .setHeader("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                        .setHeader("Origin", AtbPageParser.BASE_URL)
                        .setHeader("Referer", AtbPageParser.CATALOG_URL)
                        .setHeader("X-Requested-With", "XMLHttpRequest")
                        .setData(body));
    }

    private String requestWithRetry(APIRequestContext request, String method, String url, RequestOptions options) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                APIResponse response =
                        "POST".equals(method) ? request.post(url, options) : request.get(url, options);
                if (!response.ok()) {
                    throw new IllegalStateException("HTTP " + response.status() + " для " + url);
                }
                String text = response.text();
                if (text == null || text.isBlank()) {
                    throw new IllegalStateException("Пустой ответ: " + url);
                }
                return text;
            } catch (Exception e) {
                lastError = e;
                log.warn("Попытка {}/{}: {}", attempt, retries, e.getMessage());
                if (attempt < retries) {
                    sleep(delaySeconds * Math.pow(2, attempt - 1));
                }
            }
        }
        throw new IllegalStateException("Не удалось получить " + url + ": " + lastError);
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
