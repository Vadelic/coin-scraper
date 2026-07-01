package ru.scraper.coincatalog.mcp.tools;

import org.springaicommunity.mcp.annotation.McpTool;
import org.springaicommunity.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.mcp.CoinCatalogToolSupport;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.scraper.ScraperRegistry;

@Service
public class CoinCatalogTools {

    private final ScraperRegistry registry;
    private final CoinCatalogToolSupport support;

    public CoinCatalogTools(ScraperRegistry registry, CoinCatalogToolSupport support) {
        this.registry = registry;
        this.support = support;
    }

    @McpTool(
            name = "coin-catalog-atb",
            description =
                    "Каталог монет банка АТБ (atb.su). Возвращает JSON: список монет, цены, статус сбора. Поддерживает поиск и фильтр инвестиционных монет.")
    public String scrapeAtb(
            @McpToolParam(description = "Поиск в каталоге: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("atb", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-aurumex",
            description =
                    "Каталог монет Aurumex (aurumex.ru): JSON, только монеты в наличии, цены за единицу. Пост-поиск по query (на сайте поиска нет).")
    public String scrapeAurumex(
            @McpToolParam(description = "Пост-фильтр по названию или артикулу (на сайте поиска нет)", required = false)
                    String query) {
        return invoke("aurumex", ScrapeRequest.of(query, null, null));
    }

    @McpTool(
            name = "coin-catalog-goldenplata",
            description =
                    "Каталог монет Золотая плата (goldenplata.ru). JSON: список монет, цены, статус. Поиск (q=) и фильтр российских инвестиционных монет.")
    public String scrapeGoldenplata(
            @McpToolParam(description = "Поиск на витрине: название монеты, металл, артикул", required = false)
                    String query,
            @McpToolParam(description = "Только российские инвестиционные монеты (раздел rossiyskiye)", required = false)
                    Boolean investment_only) {
        return invoke("goldenplata", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-lanta",
            description =
                    "Каталог монет Ланта (lanta.ru). JSON: список монет, цены, статус сбора. Поиск на витрине и фильтр инвестиционных монет.")
    public String scrapeLanta(
            @McpToolParam(description = "Поиск на витрине: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("lanta", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-rshb",
            description =
                    "Каталог монет Россельхозбанка (coins.rshb.ru). JSON: sell_price и buy_price, поиск, фильтр инвестиционных монет, регион для цен продажи.")
    public String scrapeRshb(
            @McpToolParam(description = "Поиск в каталоге: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only,
            @McpToolParam(description = "Регион для цен продажи на витрине; по умолчанию Москва (код 77)", required = false)
                    String region) {
        String effectiveRegion = (region == null || region.isBlank()) ? "77" : region;
        return invoke("rshb", ScrapeRequest.of(query, investment_only, effectiveRegion));
    }

    @McpTool(
            name = "coin-catalog-sberbank",
            description =
                    "Каталог монет Сбербанка (sberbank.ru). JSON: список монет, цены продажи и выкупа, статус сбора. Поиск и фильтр инвестиционных монет.")
    public String scrapeSberbank(
            @McpToolParam(description = "Поиск в каталоге: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("sberbank", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-vtb",
            description =
                    "Каталог монет ВТБ (vtb.ru). JSON: список монет, цены продажи и выкупа, статус. Фильтр по подстроке и инвестиционные монеты.")
    public String scrapeVtb(
            @McpToolParam(description = "Узкая выборка: подстрока в названии, каталожном номере или металле", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("vtb", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-zoloto-md",
            description =
                    "Каталог монет Золотой монетный двор (spb.zoloto-md.ru). JSON: список монет, цены, статус. Поиск через query; при investment_only — country=Россия.")
    public String scrapeZolotoMd(
            @McpToolParam(description = "Поиск в каталоге: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только монеты эмитента Россия (country=Россия на витрине)", required = false)
                    Boolean investment_only) {
        return invoke("zoloto-md", ScrapeRequest.of(query, investment_only, null));
    }

    private String invoke(String slug, ScrapeRequest request) {
        return support.toJson(registry.get(slug).scrape(request));
    }
}
