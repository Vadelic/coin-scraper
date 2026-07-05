package ru.scraper.coincatalog.scraper.aurumex;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.scraper.CoinScraper.ScrapePayload;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
class AurumexIntegrationTest {

    private static final List<ExpectedCoin> PO_EXPECTED = List.of(
            new ExpectedCoin(
                    "10",
                    "Монета Георгий Победоносец, 200 рублей, 31,1 гр., СПМД/ММД, 2021-2024 г.",
                    "Золото",
                    31.1,
                    597_690.0),
            new ExpectedCoin(
                    "14",
                    "Монета Георгий Победоносец 100 рублей, 15.55 гр., ММД/СПМД, 2021-2024 г.",
                    "Золото",
                    15.55,
                    298_850.0),
            new ExpectedCoin(
                    "319",
                    "Монета Георгий Победоносец, 3 рубля, 31.1 гр., 2023-2024 г., 50 шт. (упаковка - брикет ЦБ)",
                    "Серебро",
                    1555.0,
                    16_329.8),
            new ExpectedCoin(
                    "1629",
                    "Монета Георгий Победоносец, 3 рубля, 31.1 гр., 2025 г., 50 шт. (упаковка - брикет ЦБ)",
                    "Серебро",
                    1555.0,
                    16_377.8),
            new ExpectedCoin(
                    "320",
                    "Монета Георгий Победоносец, 3 рубля, 31.1 гр.,2024 г., 100 шт., (упаковка - мешок ЦБ)",
                    "Серебро",
                    3110.0,
                    16_281.7),
            new ExpectedCoin(
                    "1630",
                    "Монета Георгий Победоносец, 3 рубля, 31.1 гр.,2025 г., 100 шт., (упаковка - мешок ЦБ)",
                    "Серебро",
                    3110.0,
                    16_329.7),
            new ExpectedCoin(
                    "292",
                    "Монета Георгий Победоносец, 50 рублей, 7.78 гр., ММД/СПМД, 2025 г.",
                    "Золото",
                    7.78,
                    148_710.0),
            new ExpectedCoin(
                    "132",
                    "Монета Георгий Победоносец, 200 руб., 31,1 г, 2021,2023 года СПМД/ММД (Механика)",
                    "Золото",
                    31.1,
                    596_240.0),
            new ExpectedCoin(
                    "1431",
                    "Монета Георгий Победоносец, 25 рублей, 3.11 гр. 2012-2023 г. (механика/патина)",
                    "Золото",
                    3.11,
                    57_220.0),
            new ExpectedCoin(
                    "135",
                    "Монета Георгий Победоносец, 50 рублей, 7.78 гр., СПМД/ММД (Механика/Патина)",
                    "Золото",
                    7.78,
                    147_150.0),
            new ExpectedCoin(
                    "214",
                    "Монета Георгий Победоносец, 200 руб., 31,1 гр., СПМД/ММД, 2023 г., 100 шт., (упаковка- мешок ЦБ)",
                    "Золото",
                    3110.0,
                    617_451.7),
            new ExpectedCoin(
                    "222",
                    "Монета Георгий Победоносец 100 руб., 15.55 гр., ММД/СПМД, 2023 г., 100 шт., (упаковка- мешок ЦБ)",
                    "Золото",
                    1555.0,
                    30_872_590.0),
            new ExpectedCoin(
                    "280",
                    "Монета Георгий Победоносец, 50 рублей, 7.78 гр., ММД/СПМД, 2025 г., 100 шт., (упаковка-мешок ЦБ)",
                    "Золото",
                    778.0,
                    15_172_840.0),
            new ExpectedCoin(
                    "281",
                    "Монета Георгий Победоносец, 50 рублей, 7.78 гр., ММД/СПМД, 2018-2024 г., 100 шт., (упаковка-мешок ЦБ)",
                    "Золото",
                    778.0,
                    15_132_640.0),
            new ExpectedCoin(
                    "213",
                    "Монета Георгий Победоносец, 25 рублей, 3.11 гр. 2023 г., 100 шт., (упаковка-мешок ЦБ)",
                    "Золото",
                    311.0,
                    6_341_660.0));

    private static final List<ExpectedCoin> PO_CATALOG_EXTRA = List.of(
            new ExpectedCoin(
                    "314",
                    "Монета Атомный ледокол \"Сибирь\", 200 рублей, СПМД, 2024 г.",
                    "Золото",
                    31.1,
                    707_130.0),
            new ExpectedCoin(
                    "31",
                    "Монета Князь Александр Невский, 5000 франков, 31.1 гр., ММД, 2023 г.",
                    "Золото",
                    31.1,
                    510_460.0),
            new ExpectedCoin(
                    "28",
                    "Монета Золотой червонец,10 рублей, 7,78 гр., СПМД, 2023 г.",
                    "Золото",
                    7.78,
                    150_330.0),
            new ExpectedCoin(
                    "1429",
                    "Монета Орден Красной Звезды, 3 рубля, СПМД, 2024",
                    "Серебро",
                    31.1,
                    48_030.0),
            new ExpectedCoin(
                    "1430",
                    "Монета 10-летие ЕАЭС, 3 рубля, СПМД, 2024",
                    "Серебро",
                    31.1,
                    17_780.0),
            new ExpectedCoin(
                    "313",
                    "Монета Атомный ледокол \"Сибирь\", 3 рубля, СПМД, 2024 г.",
                    "Серебро",
                    31.1,
                    33_620.0),
            new ExpectedCoin(
                    "1428",
                    "Монета Пианистка, педагог Е.Ф. Гнесина, к 150-летию со дня рождения, 2 рубля, СПМД, 2024 г.",
                    "Серебро",
                    15.55,
                    8890.0),
            new ExpectedCoin(
                    "37",
                    "Монета Сеятель, один червонец, 7.74 гр, 1975-1982 гг.",
                    "Золото",
                    7.74,
                    150_390.0),
            new ExpectedCoin(
                    "206",
                    "Монета Золотой червонец,10 рублей, 7,78 гр., СПМД, 2023 г., 100 шт., (упаковка-мешок ЦБ)",
                    "Золото",
                    778.0,
                    15_486_430.0),
            new ExpectedCoin(
                    "1515",
                    "Монета Золотой червонец,10 рублей, 7,78 гр., ММД, 2023 г., 100 шт., (упаковка-мешок ЦБ)",
                    "Золото",
                    778.0,
                    15_960_830.0),
            new ExpectedCoin(
                    "1648",
                    "Монета Хоккей, 3 рубля, СПМД, 2025 г.",
                    "Серебро",
                    31.1,
                    17_780.0),
            new ExpectedCoin(
                    "1647",
                    "Монета 200-летие основания г. Черкесска, 3 рубля, СПМД, 2025 г.",
                    "Серебро",
                    31.1,
                    17_780.0),
            new ExpectedCoin(
                    "1646",
                    "Монета Первый русский профессиональный театр, 3 рубля, СПМД, 2025 г.",
                    "Серебро",
                    31.1,
                    17_780.0),
            new ExpectedCoin(
                    "1645",
                    "Монета 100-летие Всероссийского общества слепых, 3 рубля, СПМД, 2025 г.",
                    "Серебро",
                    31.1,
                    17_780.0));

    // https://aurumex.ru/catalog?availability=true — пост-фильтр query=по
    @Test
    void poInvestmentCatalog() {
        ScrapePayload<Coin> payload =
                new AurumexScraper().scrape("по", false, null);
        assertCatalog(payload, PO_EXPECTED, 11, 4, 2, PO_EXPECTED.size());
    }

    // https://aurumex.ru/catalog?availability=true — весь каталог в наличии
    @Test
    void poCatalog() {
        ScrapePayload<Coin> payload =
                new AurumexScraper().scrape("", false, null);
        List<ExpectedCoin> expected =
                Stream.concat(PO_EXPECTED.stream(), PO_CATALOG_EXTRA.stream()).toList();
        assertCatalog(payload, expected, 17, 12, 2, expected.size());
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
            assertThat(coin.buyPrice()).isNull();
            assertThat(coin.sellPrice()).isEqualTo(exp.sellPrice());
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
            Double sellPrice) {}
}
