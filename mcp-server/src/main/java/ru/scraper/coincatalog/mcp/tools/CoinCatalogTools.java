package ru.scraper.coincatalog.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.model.ScrapeStatus;
import ru.scraper.coincatalog.scraper.ScraperRegistry;

import java.util.List;

@Service
public class CoinCatalogTools {

    private final ScraperRegistry registry;

    public CoinCatalogTools(ScraperRegistry registry) {
        this.registry = registry;
    }

    @McpTool(
            name = "coin-catalog-atb",
            description =
                    "Каталог монет банка АТБ (atb.su). Список монет с ценами. Поддерживает поиск и фильтр инвестиционных монет.")
    public List<Coin> scrapeAtb(
            @McpToolParam(description = "Поиск в каталоге: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("atb", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-aurumex",
            description =
                    "Каталог монет Aurumex (aurumex.ru): только монеты в наличии. Пост-поиск по query (на сайте поиска нет).")
    public List<Coin> scrapeAurumex(
            @McpToolParam(description = "Пост-фильтр по названию или артикулу (на сайте поиска нет)", required = false)
                    String query) {
        return invoke("aurumex", ScrapeRequest.of(query, null, null));
    }

    @McpTool(
            name = "coin-catalog-goldenplata",
            description =
                    "Каталог монет Золотая плата (goldenplata.ru). Поиск (q=) и фильтр российских инвестиционных монет.")
    public List<Coin> scrapeGoldenplata(
            @McpToolParam(description = "Поиск на витрине: название монеты, металл, артикул", required = false)
                    String query,
            @McpToolParam(description = "Только российские инвестиционные монеты (раздел rossiyskiye)", required = false)
                    Boolean investment_only) {
        return invoke("goldenplata", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-lanta",
            description =
                    "Каталог монет Ланта (lanta.ru). Поиск на витрине и фильтр инвестиционных монет.")
    public List<Coin> scrapeLanta(
            @McpToolParam(description = "Поиск на витрине: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("lanta", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-rshb",
            description =
                    "Каталог монет Россельхозбанка (coins.rshb.ru). sell_price и buy_price; поиск, инвестиционные монеты, регион.")
    public List<Coin> scrapeRshb(
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
                    "Каталог монет Сбербанка (sberbank.ru). Цены продажи и выкупа; поиск и фильтр инвестиционных монет.")
    public List<Coin> scrapeSberbank(
            @McpToolParam(description = "Поиск в каталоге: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("sberbank", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-vtb",
            description =
                    "Каталог монет ВТБ (vtb.ru). Цены продажи и выкупа; фильтр по подстроке и инвестиционные монеты.")
    public List<Coin> scrapeVtb(
            @McpToolParam(description = "Узкая выборка: подстрока в названии, каталожном номере или металле", required = false)
                    String query,
            @McpToolParam(description = "Только инвестиционные монеты (без памятных и коллекционных)", required = false)
                    Boolean investment_only) {
        return invoke("vtb", ScrapeRequest.of(query, investment_only, null));
    }

    @McpTool(
            name = "coin-catalog-zoloto-md",
            description =
                    "Каталог монет Золотой монетный двор (spb.zoloto-md.ru). Поиск через query; при investment_only — country=Россия.")
    public List<Coin> scrapeZolotoMd(
            @McpToolParam(description = "Поиск в каталоге: название монеты, металл, каталожный номер", required = false)
                    String query,
            @McpToolParam(description = "Только монеты эмитента Россия (country=Россия на витрине)", required = false)
                    Boolean investment_only) {
        return invoke("zoloto-md", ScrapeRequest.of(query, investment_only, null));
    }

    private List<Coin> invoke(String slug, ScrapeRequest request) {
        ScrapeResult result = registry.get(slug).scrape(request);
        if (result.scrapeStatus() != ScrapeStatus.OK) {
            throw new IllegalStateException(result.error() != null ? result.error() : result.scrapeStatus().value());
        }
        return result.coins();
    }
}
