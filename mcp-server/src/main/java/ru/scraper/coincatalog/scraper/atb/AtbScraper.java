package ru.scraper.coincatalog.scraper.atb;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.CaptchaBlockedException;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;
import ru.scraper.coincatalog.scraper.support.HttpScrapeClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Скрапер каталога АТБ через HTTP и AJAX-фрагменты. */
@Slf4j
@Service("ATB")
public class AtbScraper implements CoinScraper<Coin> {

    private final HttpScrapeClient http;
    private final int retries;
    private final double delaySeconds;
    private final Function<String, String> detailLoaderOverride;

    public AtbScraper() {
        this(HttpScrapeClient.defaults(), null);
    }

    AtbScraper(Function<String, String> detailLoaderOverride) {
        this(HttpScrapeClient.defaults(), detailLoaderOverride);
    }

    AtbScraper(HttpScrapeClient http, Function<String, String> detailLoaderOverride) {
        this(http, 3, 0.35, detailLoaderOverride);
    }

    AtbScraper(
            HttpScrapeClient http,
            int retries,
            double delaySeconds,
            Function<String, String> detailLoaderOverride) {
        this.http = http;
        this.retries = Math.max(1, retries);
        this.delaySeconds = Math.max(0, delaySeconds);
        this.detailLoaderOverride = detailLoaderOverride;
    }

    @Override
    public ScrapePayload<Coin> scrape(String query, boolean investmentOnly, String region) {
        String category = AtbPageParser.resolveCategory(investmentOnly);
        String normalizedQuery = query != null ? query.strip() : "";

        log.info("Открываю каталог: {}", AtbPageParser.CATALOG_URL);
        String mainHtml = requestWithRetry(
                "GET",
                AtbPageParser.CATALOG_URL,
                null,
                HttpScrapeClient.htmlGetHeaders(AtbPageParser.BASE_URL + "/"));
        if (AtbPageParser.detectCaptcha(mainHtml)) {
            throw new CaptchaBlockedException("CAPTCHA на странице каталога ATB");
        }

        String fragment = fetchAjaxFragment(category, normalizedQuery);
        List<Coin> coins = parseCoinsFromFragment(fragment);
        return buildResult(fragment, coins);
    }

    static ScrapePayload<Coin> buildResult(String fragment, List<Coin> coins) {
        if (coins.isEmpty()) {
            if (AtbPageParser.isEmptySearchResult(fragment)) {
                return new ScrapePayload<>(1, List.of());
            }
            throw new IllegalStateException("Не удалось распарсить монеты из ответа");
        }
        return new ScrapePayload<>(1, coins);
    }

    private List<Coin> parseCoinsFromFragment(String fragmentHtml) {
        List<AtbPageParser.CardMatch> cards = AtbPageParser.parseCardsFromFragment(fragmentHtml);
        log.info("Карточек в ответе: {}", cards.size());

        List<Coin> coins = new ArrayList<>();
        Set<String> seenUrls = new HashSet<>();
        int index = 0;
        for (AtbPageParser.CardMatch card : cards) {
            index++;
            if (delaySeconds > 0 && index > 1) {
                sleep(delaySeconds);
            }
            String detailHtml = loadDetailHtml(card.href());
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

    private String loadDetailHtml(String href) {
        String url = href.startsWith("http") ? href : AtbPageParser.BASE_URL + href;
        if (detailLoaderOverride != null) {
            return detailLoaderOverride.apply(url);
        }
        return requestWithRetry(
                "GET",
                url,
                null,
                HttpScrapeClient.htmlGetHeaders(AtbPageParser.CATALOG_URL));
    }

    private String fetchAjaxFragment(String category, String query) {
        String body = AtbPageParser.buildRequestBody(category, query);
        return requestWithRetry(
                "POST",
                AtbPageParser.CATALOG_URL,
                body.getBytes(StandardCharsets.UTF_8),
                HttpScrapeClient.formHeaders(AtbPageParser.BASE_URL, AtbPageParser.CATALOG_URL));
    }

    private String requestWithRetry(String method, String url, byte[] body, java.util.Map<String, String> headers) {
        Exception lastError = null;
        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                return http.requestText(method, url, body, headers);
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

    private static void sleep(double seconds) {
        try {
            Thread.sleep((long) (seconds * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during delay", e);
        }
    }
}
