package ru.scraper.coincatalog.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.scraper.coincatalog.application.ScrapeRegistry;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeRequest;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.model.ScrapeSource;
import ru.scraper.coincatalog.model.ScrapeStatus;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoinCatalogTools {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String COMMON_RETURN = """
            Возвращает: массив монет с полями catalogNumber, name, metal, weightG, \
            buyPrice (выкуп), sellPrice (продажа). При ошибке сбора или CAPTCHA — \
            исключение с текстом ошибки (не возвращает частичный JSON).""";

    private static final String COMMON_INVESTMENT_RECOMMENDATION = """
            Рекомендация: investmentOnly=true по умолчанию (только инвестиционные монеты), \
            если пользователь явно не просит весь каталог.""";

    private static final String QUERY_PARAM = """
            Необязательный фильтр по подстроке в названии, металле или каталожном номере. \
            Примеры: «победоносец», «золото», «5216-0060». Пусто или omit — вернуть весь \
            каталог (с учётом investmentOnly).""";

    private static final String INVESTMENT_ONLY_PARAM = """
            Необязательный. true — только инвестиционные монеты (рекомендуется по умолчанию). \
            false или omit — весь каталог, включая памятные и коллекционные. Передавайте true, \
            если пользователь не просил «все монеты».""";

    private final ScrapeRegistry scrapeRegistry;

    @McpTool(
            name = "coin-catalog-atb",
            title = "АТБ: каталог монет",
            description =
                    """
                    Актуальный каталог монет банка АТБ (atb.su).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в АТБ, \
                    поиск по названию, металлу или артикулу, сравнение котировок.

                    """
                            + COMMON_RETURN
                            + "\n\n"
                            + COMMON_INVESTMENT_RECOMMENDATION
                            + """

                    Особенности: HTTP (AJAX fragment + detail GET); sellPrice; поиск на витрине через query.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "АТБ: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeAtb(
            @McpToolParam(description = QUERY_PARAM, required = false) String query,
            @McpToolParam(description = INVESTMENT_ONLY_PARAM, required = false) Boolean investmentOnly) {
        return invoke(ScrapeSource.ATB, ScrapeRequest.of(query, investmentOnly, null));
    }

    @McpTool(
            name = "coin-catalog-aurumex",
            title = "Aurumex: каталог монет",
            description =
                    """
                    Актуальный каталог монет Aurumex (aurumex.ru).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в Aurumex, \
                    поиск по названию или артикулу.

                    """
                            + COMMON_RETURN
                            + """

                    Особенности: HTTP (Nuxt _payload.json); на сайте нет поля поиска — query применяется \
                    как пост-фильтр после загрузки каталога.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Aurumex: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeAurumex(
            @McpToolParam(
                            description =
                                    """
                                    Необязательный пост-фильтр по названию или артикулу после загрузки каталога \
                                    (на сайте нет поля поиска). Пусто — все монеты в наличии.""",
                            required = false)
                    String query) {
        return invoke(ScrapeSource.AURUMEX, ScrapeRequest.of(query, null, null));
    }

    @McpTool(
            name = "coin-catalog-goldenplata",
            title = "Золотая плата: каталог монет",
            description =
                    """
                    Актуальный каталог монет Золотая плата (goldenplata.ru).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в Золотой \
                    плате, поиск по названию, металлу или артикулу.

                    """
                            + COMMON_RETURN
                            + "\n\n"
                            + COMMON_INVESTMENT_RECOMMENDATION
                            + """

                    Особенности: HTTP (analytics JSON в HTML); investmentOnly ограничивает разделом российских \
                    инвестиционных монет (rossiyskiye).""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Золотая плата: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeGoldenplata(
            @McpToolParam(description = QUERY_PARAM, required = false) String query,
            @McpToolParam(
                            description =
                                    """
                                    Необязательный. true — только российские инвестиционные монеты (раздел rossiyskiye). \
                                    false или omit — весь каталог. Передавайте true, если пользователь не просил «все монеты».""",
                            required = false)
                    Boolean investmentOnly) {
        return invoke(ScrapeSource.GOLDENPLATA, ScrapeRequest.of(query, investmentOnly, null));
    }

    @McpTool(
            name = "coin-catalog-lanta",
            title = "Ланта: каталог монет",
            description =
                    """
                    Актуальный каталог монет банка Ланта (lanta.ru).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в Ланта, \
                    поиск по названию, металлу или артикулу.

                    """
                            + COMMON_RETURN
                            + "\n\n"
                            + COMMON_INVESTMENT_RECOMMENDATION
                            + """

                    Особенности: Playwright; путь к браузеру — --browser=/path/to/chrome; \
                    при CAPTCHA — --lanta.headful=true; сессия LANTA_STORAGE_STATE / \
                    data/lanta-storage-state.json.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Ланта: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeLanta(
            @McpToolParam(description = QUERY_PARAM, required = false) String query,
            @McpToolParam(description = INVESTMENT_ONLY_PARAM, required = false) Boolean investmentOnly) {
        return invoke(ScrapeSource.LANTA, ScrapeRequest.of(query, investmentOnly, null));
    }

    @McpTool(
            name = "coin-catalog-rshb",
            title = "Россельхозбанк: каталог монет",
            description =
                    """
                    Актуальный каталог монет Россельхозбанка (coins.rshb.ru).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в РСХБ, \
                    поиск по названию, металлу или артикулу, цены в конкретном регионе.

                    """
                            + COMMON_RETURN
                            + "\n\n"
                            + COMMON_INVESTMENT_RECOMMENDATION
                            + """

                    Особенности: HTTP (SSR HTML + ES buyout API); sellPrice и buyPrice; параметр region задаёт \
                    регион для цен продажи (по умолчанию Москва, код 77).""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Россельхозбанк: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeRshb(
            @McpToolParam(description = QUERY_PARAM, required = false) String query,
            @McpToolParam(description = INVESTMENT_ONLY_PARAM, required = false) Boolean investmentOnly,
            @McpToolParam(
                            description =
                                    """
                                    Необязательный код региона для цен продажи на витрине РСХБ. \
                                    По умолчанию «77» (Москва). Пример: «77», «78».""",
                            required = false)
                    String region) {
        String effectiveRegion = (region == null || region.isBlank()) ? "77" : region;
        return invoke(ScrapeSource.RSHB, ScrapeRequest.of(query, investmentOnly, effectiveRegion));
    }

    @McpTool(
            name = "coin-catalog-sberbank",
            title = "Сбербанк: каталог монет",
            description =
                    """
                    Актуальный каталог монет Сбербанка (sberbank.ru/ru/person/mon).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в Сбербанке, \
                    Sberbank coins, поиск по названию («георгий победоносец»), металлу или артикулу \
                    (5216-0060), сравнение цен продажи и выкупа.

                    """
                            + COMMON_RETURN
                            + "\n\n"
                            + COMMON_INVESTMENT_RECOMMENDATION
                            + """

                    Особенности: HTTP API без браузера; sellPrice и buyPrice; query — \
                    локальный фильтр по полному каталогу.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Сбербанк: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeSberbank(
            @McpToolParam(description = QUERY_PARAM, required = false) String query,
            @McpToolParam(description = INVESTMENT_ONLY_PARAM, required = false) Boolean investmentOnly) {
        return invoke(ScrapeSource.SBERBANK, ScrapeRequest.of(query, investmentOnly, null));
    }

    @McpTool(
            name = "coin-catalog-vtb",
            title = "ВТБ: каталог монет",
            description =
                    """
                    Актуальный каталог монет ВТБ (vtb.ru).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в ВТБ, \
                    поиск по названию, металлу или артикулу, сравнение котировок.

                    """
                            + COMMON_RETURN
                            + "\n\n"
                            + COMMON_INVESTMENT_RECOMMENDATION
                            + """

                    Особенности: HTTP + BFF API; sellPrice и buyPrice; query — \
                    пост-фильтр по подстроке в названии, каталожном номере или металле.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "ВТБ: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeVtb(
            @McpToolParam(description = QUERY_PARAM, required = false) String query,
            @McpToolParam(description = INVESTMENT_ONLY_PARAM, required = false) Boolean investmentOnly) {
        return invoke(ScrapeSource.VTB, ScrapeRequest.of(query, investmentOnly, null));
    }

    @McpTool(
            name = "coin-catalog-zoloto-md",
            title = "Золотой монетный двор: каталог монет",
            description =
                    """
                    Актуальный каталог монет Золотой монетный двор (spb.zoloto-md.ru).

                    Когда вызывать: пользователь спрашивает о ценах или наличии монет в Золотом \
                    монетном дворе, поиск по названию, металлу или артикулу.

                    """
                            + COMMON_RETURN
                            + "\n\n"
                            + COMMON_INVESTMENT_RECOMMENDATION
                            + """

                    Особенности: HTTP; investmentOnly включает фильтр \
                    country=Россия на витрине.""",
            generateOutputSchema = true,
            annotations = @McpTool.McpAnnotations(
                    title = "Золотой монетный двор: каталог монет",
                    readOnlyHint = true,
                    destructiveHint = false,
                    idempotentHint = false,
                    openWorldHint = true))
    public List<Coin> scrapeZolotoMd(
            @McpToolParam(description = QUERY_PARAM, required = false) String query,
            @McpToolParam(
                            description =
                                    """
                                    Необязательный. true — только монеты эмитента Россия (country=Россия на витрине). \
                                    false или omit — весь каталог. Передавайте true, если пользователь не просил «все монеты».""",
                            required = false)
                    Boolean investmentOnly) {
        return invoke(ScrapeSource.ZOLOTO_MD, ScrapeRequest.of(query, investmentOnly, null));
    }

    private List<Coin> invoke(ScrapeSource source, ScrapeRequest request) {
        log.info(
                """
                MCP scrape request:
                source={}
                query={}
                investmentOnly={}
                region={}""",
                source,
                request.query().orElse(null),
                request.investmentOnly().orElse(null),
                request.region().orElse(null));
        ScrapeResult<Coin> result = scrapeRegistry.run(source, request);
        try {
            log.info(
                    "MCP scrape {} response:\n{}",
                    source,
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(result));
        } catch (Exception e) {
            log.warn("MCP scrape {} response log failed: {}", source, e.getMessage());
        }
        if (result.scrapeStatus() != ScrapeStatus.OK) {
            throw new IllegalStateException(result.error() != null ? result.error() : result.scrapeStatus().value());
        }
        return result.coins();
    }
}
