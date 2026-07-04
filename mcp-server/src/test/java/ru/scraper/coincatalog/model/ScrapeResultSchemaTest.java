package ru.scraper.coincatalog.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ScrapeResultSchemaTest {

    private static JsonSchemaValidator validator;
    private static Map<String, Object> schema;
    private static JsonMapper objectMapper;

    @BeforeAll
    static void setUp() throws Exception {
        objectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        validator = new DefaultJsonSchemaValidator(objectMapper);
        try (InputStream in =
                ScrapeResultSchemaTest.class.getResourceAsStream("/coins_catalog.schema.json")) {
            String schemaText = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            schema = objectMapper.readValue(schemaText, new TypeReference<>() {});
        }
        assertThat(validator.validateSchema(schema).valid()).isTrue();
    }

    @Test
    void okResultPassesSchema() {
        var request = ScrapeRequest.of("золото", true, null);
        var coin = new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 89500.0, 99700.0);
        var result = ScrapeResult.ok(request, 1, List.of(coin));

        assertValid(result);
    }

    @Test
    void errorResultPassesSchema() {
        var request = ScrapeRequest.of(null, null, null);
        var result = ScrapeResult.error(request, "test failure");

        assertValid(result);
    }

    @Test
    void captchaBlockedPassesSchema() {
        var request = ScrapeRequest.of("победоносец", true, null);
        var result = ScrapeResult.captchaBlocked(request, "CAPTCHA detected");

        assertValid(result);
    }

    @Test
    void notImplementedPassesSchema() {
        var request = ScrapeRequest.of(null, true, null);
        var result = ScrapeResult.notImplemented(request);

        assertValid(result);
    }

    @Test
    void coinSerializesWithCamelCaseKeys() throws Exception {
        var coin = new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 89500.0, 99700.0);
        String json = objectMapper.writeValueAsString(coin);

        assertThat(json)
                .contains("\"catalogNumber\"")
                .contains("\"weightG\"")
                .contains("\"buyPrice\"")
                .contains("\"sellPrice\"")
                .doesNotContain("catalog_number")
                .doesNotContain("weight_g");
    }

    private void assertValid(ScrapeResult<?> result) {
        Map<String, Object> instance = objectMapper.convertValue(result, new TypeReference<>() {});
        JsonSchemaValidator.ValidationResponse response = validator.validate(schema, instance);
        assertThat(response.valid())
                .as("validation errors: %s", response.errorMessage())
                .isTrue();
    }
}
