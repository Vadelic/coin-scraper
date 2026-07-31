package ru.scraper.coincatalog.scraper.zolotmd;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.zolotmd.HttpFetcher;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class ZolotoMdIntegrationTest {

    private final ZolotoMdScraper scraper = new ZolotoMdScraper();

    private static final List<ExpectedCoin> PO_EXPECTED = List.of(
            new ExpectedCoin(
                    "18188",
                    "Золотая монета России \"Георгий Победоносец\" 2026 г.в., 7.78 г чистого золота (проба 999)",
                    "Золото",
                    7.78,
                    89_410.0,
                    97_329.0),
            new ExpectedCoin(
                    "15045",
                    "Золотой червонец Сеятель, 2023 г.в., вес чистого золота - 7.78 г (проба 999)",
                    "Золото",
                    7.78,
                    88_559.0,
                    101_587.0));

    private static final List<ExpectedCoin> PO_CATALOG_EXTRA = List.of(
            new ExpectedCoin(
                    "19058",
                    "Золотая монета Камеруна \"Верность и Доблесть\" 2026 г.в., 7.78 г чистого золота (проба 9999)",
                    "Золото",
                    7.78,
                    88_559.0,
                    97_925.0),
            new ExpectedCoin(
                    "12870",
                    "Золотая монета Камеруна \"Вепрь\" 2022 г.в., 7.78 г чистого золота (Проба 9999)",
                    "Золото",
                    7.78,
                    90_262.0,
                    96_478.0),
            new ExpectedCoin(
                    "19950",
                    "Золотая монета Австрии \"Филармоникер\" 2026 г.в., 7.78 г чистого золота (проба 9999)",
                    "Золото",
                    7.78,
                    89_410.0,
                    99_203.0),
            new ExpectedCoin(
                    "18786",
                    "Золотая монета Великобритании \"Звери Тюдоров. Грейхаунд Ричмонда\" 2025 г.в., 7.78 г чистого золота (проба 9999)",
                    "Золото",
                    7.78,
                    null,
                    98_777.0),
            new ExpectedCoin(
                    "18309",
                    "Золотая монета Великобритании \"Звери Тюдоров. Королевская Пантера\" 2025 г.в., 7.78 г чистого золота (проба 9999)",
                    "Золото",
                    7.78,
                    null,
                    98_777.0),
            new ExpectedCoin(
                    "584",
                    "Золотая инвестиционная монета Канады \"Кленовый Лист\", 7.78 г чистого золота (проба 9999)",
                    "Золото",
                    7.78,
                    87_707.0,
                    98_351.0),
            new ExpectedCoin(
                    "19316",
                    "Золотая монета Австралии \"Кенгуру\" 2026 г.в., 31.1 г чистого золота (проба 9999)",
                    "Золото",
                    31.1,
                    360_815.0,
                    384_642.0),
            new ExpectedCoin(
                    "15580",
                    "Золотая инвестиционная монета Великобритании \"Британия (Чарльз III)\" , 31.1 г чистого золота (проба 9999)",
                    "Золото",
                    31.1,
                    355_709.0,
                    376_132.0),
            new ExpectedCoin(
                    "19725",
                    "Золотая монета Австрии \"Филармоникер\" 2026 г.в., 31.1 г чистого золота (проба 9999)",
                    "Золото",
                    31.1,
                    360_815.0,
                    384_642.0),
            new ExpectedCoin(
                    "576",
                    "Золотая инвестиционная монета США Американский Орел, 31,1 гр чистого золота (проба 917)",
                    "Золото",
                    31.1,
                    355_709.0,
                    383_621.0),
            new ExpectedCoin(
                    "20548",
                    "Серебряная монета Австралии \"Коала\" 2026 г.в., 31.1 г чистого серебра (проба 9999)",
                    "Серебро",
                    31.1,
                    null,
                    7_882.0),
            new ExpectedCoin(
                    "20434",
                    "Серебряная монета Фиджи \"Единорог\" 2025 г.в., 31.1 г чистого серебра (проба 999)",
                    "Серебро",
                    31.1,
                    null,
                    7_882.0),
            new ExpectedCoin(
                    "20320",
                    "Серебряная монета Австралии \"Лебедь. 10-летний юбилей\" 2026 г.в., 31.1 г чистого серебра (проба 9999)",
                    "Серебро",
                    31.1,
                    null,
                    10_679.0),
            new ExpectedCoin(
                    "20347",
                    "Серебряная монета ЮАР \"Большая пятерка – III. Леопард\" 2026 г.в. (блистер), 31.1 г чистого серебра (проба 999)",
                    "Серебро",
                    31.1,
                    null,
                    14_239.0),
            new ExpectedCoin(
                    "675",
                    "Серебряная монета Австрии \"Венский Филармоникер\", 31.1 г чистого серебра (проба 999)",
                    "Серебро",
                    31.1,
                    4_577.0,
                    7_882.0),
            new ExpectedCoin(
                    "13574",
                    "Серебряная монета США \"Американский Орел\" (Тип 2), 31.1 г чистого серебра (Проба 999)",
                    "Серебро",
                    31.1,
                    4_577.0,
                    7_882.0),
            new ExpectedCoin(
                    "3187",
                    "Серебряная инвестиционная монета США \"Американский Орел\" 31.1 г чистого серебра (проба 999)",
                    "Серебро",
                    31.1,
                    4_577.0,
                    7_882.0),
            new ExpectedCoin(
                    "16830",
                    "Серебряная монета Великобритании \"Королевский Герб (Чарльз III)\" 2023 г.в., 31.1 г чистого серебра (проба 999)",
                    "Серебро",
                    31.1,
                    4_577.0,
                    7_882.0),
            new ExpectedCoin(
                    "15394",
                    "Серебряная монета Великобритании \"Британия (Чарльз III)\", 31.1 г чистого серебра (проба 999)",
                    "Серебро",
                    31.1,
                    4_577.0,
                    7_882.0),
            new ExpectedCoin(
                    "7582",
                    "Серебряная монета ЮАР \"Крюгерранд\", 31.1 г чистого серебра (Проба 999)",
                    "Серебро",
                    31.1,
                    4_577.0,
                    7_882.0),
            new ExpectedCoin(
                    "673",
                    "Серебряная монета Канады \"Кленовый лист\", 31.1 г чистого серебра (проба 9999)",
                    "Серебро",
                    31.1,
                    4_577.0,
                    7_882.0),
            new ExpectedCoin(
                    "19308",
                    "Серебряная монета Великобритании \"Британский лев\" 2025 г.в., 31.1 г чистого серебра (проба 999)",
                    "Серебро",
                    31.1,
                    null,
                    9_154.0));

    // https://spb.zoloto-md.ru/catalog?page=1&limit=100&available=1&country=Россия&query=по
    @Test
    void poInvestmentCatalog() {
        ScrapePayload<Coin> payload = scraper.scrape("по", true, null);
        List<ExpectedCoin> expected =
                Stream.concat(PO_EXPECTED.stream(), PO_CATALOG_EXTRA.stream()).toList();
        assertCatalog(payload, expected, 12, 12, 1, expected.size());
    }

    // https://spb.zoloto-md.ru/catalog?page=1&limit=100&available=1&query=по
    @Test
    void poCatalog() {
        ScrapePayload<Coin> payload = scraper.scrape("по", false, null);
        List<ExpectedCoin> expected =
                Stream.concat(PO_EXPECTED.stream(), PO_CATALOG_EXTRA.stream()).toList();
        assertCatalog(payload, expected, 12, 12, 1, expected.size());
    }

    private static void assertCatalog(
            ScrapePayload<Coin> payload,
            List<ExpectedCoin> expected,
            int goldCount,
            int silverCount,
            int pagesProcessed,
            int totalCoins) {
        List<Coin> coins = payload.coins();

        assertThat(payload.pagesProcessed()).isEqualTo(pagesProcessed);
        assertThat(coins.stream().filter(c -> "Серебро".equals(c.metal()))).hasSize(silverCount);
        assertThat(coins.stream().filter(c -> "Золото".equals(c.metal()))).hasSize(goldCount);
        assertThat(coins).hasSize(totalCoins);

        for (ExpectedCoin exp : expected) {
            Coin coin = findCoin(coins, exp);
            assertThat(coin.name()).isEqualTo(exp.name());
            assertThat(coin.metal()).isEqualTo(exp.metal());
            if (exp.weightG() != null) {
                assertThat(coin.weightG()).isEqualTo(exp.weightG());
            } else {
                assertThat(coin.weightG()).isNull();
            }
            assertThat(coin.buyPrice()).isEqualTo(exp.buyPrice());
            assertThat(coin.sellPrice()).isEqualTo(exp.sellPrice());
            if (coin.buyPrice() != null && coin.sellPrice() != null) {
                assertThat(coin.buyPrice()).isLessThan(coin.sellPrice());
            }
        }
    }

    private static Coin findCoin(List<Coin> coins, ExpectedCoin expected) {
        return coins.stream()
                .filter(c -> expected.catalogNumber().equals(c.catalogNumber()))
                .filter(c -> expected.name().equals(c.name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Coin not found: " + expected));
    }

    private record ExpectedCoin(
            String catalogNumber,
            String name,
            String metal,
            Double weightG,
            Double buyPrice,
            Double sellPrice) {}
}
