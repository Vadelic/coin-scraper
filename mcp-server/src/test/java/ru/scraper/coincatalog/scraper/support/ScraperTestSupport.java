package ru.scraper.coincatalog.scraper.support;

import lombok.experimental.UtilityClass;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.networknt.schema.Error;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import ru.scraper.coincatalog.model.Coin;
import ru.scraper.coincatalog.model.ScrapeResult;
import ru.scraper.coincatalog.model.ScrapeSource;
import ru.scraper.coincatalog.model.ScrapeStatus;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class ScraperTestSupport {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();
    private static final Schema SCHEMA = loadSchema();

    public static List<Error> validateSchema(ScrapeResult<Coin> result) throws Exception {
        String json = MAPPER.writeValueAsString(result);
        return SCHEMA.validate(json, InputFormat.JSON);
    }

    public static void assertOkWithCoins(ScrapeResult<Coin> result) throws Exception {
        assertThat(result.scrapeStatus()).isEqualTo(ScrapeStatus.OK);
        assertThat(result.totalCoins()).isGreaterThan(0);
        assertThat(result.coins()).isNotEmpty();
        assertThat(validateSchema(result)).isEmpty();
        assertThat(result.coins().get(0).name()).isNotBlank();
    }

    public static void assertLiveResult(ScrapeResult<Coin> result, ScrapeSource source) throws Exception {
        assertThat(result.scrapeStatus())
                .as("%s scrapeStatus", source.name())
                .isIn(ScrapeStatus.OK, ScrapeStatus.CAPTCHA_BLOCKED);
        assertThat(validateSchema(result)).isEmpty();
        if (result.scrapeStatus() == ScrapeStatus.OK) {
            assertThat(result.totalCoins())
                    .as("%s totalCoins", source.name())
                    .isGreaterThan(0);
            assertThat(result.coins().get(0).name()).isNotBlank();
        }
    }

    public static String loadFixture(Class<?> testClass, String bank, String name) throws Exception {
        try (InputStream in = testClass.getResourceAsStream("/fixtures/" + bank + "/" + name)) {
            return new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Schema loadSchema() {
        try {
            SchemaRegistry registry =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
            try (InputStream in = ScraperTestSupport.class.getResourceAsStream("/coins_catalog.schema.json")) {
                String schemaText =
                        new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
                return registry.getSchema(schemaText, InputFormat.JSON);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load coins_catalog.schema.json", e);
        }
    }
}
