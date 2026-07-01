package ru.scraper.coincatalog.scraper.common;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;

class PriceParserTest {

    @ParameterizedTest
    @CsvSource({
        "'99 700 ₽', 99700.0",
        "'89 500,50', 89500.5",
        "'1 234 567', 1234567.0",
        "'0', 0.0"
    })
    void parsesRubPrices(String input, double expected) {
        assertThat(PriceParser.parseRub(input)).isEqualTo(expected);
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
