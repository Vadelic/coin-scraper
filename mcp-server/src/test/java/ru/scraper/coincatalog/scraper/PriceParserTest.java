package ru.scraper.coincatalog.scraper;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import ru.scraper.coincatalog.scraper.PriceParser;

import static org.assertj.core.api.Assertions.assertThat;

class PriceParserTest {

    @ParameterizedTest
    @CsvSource({
        "'99 700 ₽', 99700.0",
        "'89 500,50', 89500.5",
        "'1 234 567', 1234567.0",
        "'0', 0.0",
        "'99\u00a0700\u00a0₽', 99700.0"
    })
    void parsesRubPrices(String input, double expected) {
        Assertions.assertThat(PriceParser.parseRub(input)).isEqualTo(expected);
    }

    @Test
    void parseLastRubUsesFinalAmount() {
        assertThat(PriceParser.parseLastRub("100 200 ₽ 93 500 ₽")).isEqualTo(93500.0);
        assertThat(PriceParser.parseLastRub("83 000 ₽ 81 500 ₽ покупка")).isEqualTo(81500.0);
    }

    @ParameterizedTest
    @NullAndEmptySource
    void nullOrBlankReturnsNull(String input) {
        assertThat(PriceParser.parseRub(input)).isNull();
    }

    @ParameterizedTest
    @CsvSource({"'нет цены'", "'—'"})
    void unparseableReturnsNull(String input) {
        assertThat(PriceParser.parseRub(input)).isNull();
    }
}
