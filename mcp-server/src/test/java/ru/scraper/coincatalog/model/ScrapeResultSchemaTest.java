package ru.scraper.coincatalog.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

class ScrapeResultSchemaTest {

    private static Schema schema;
    private static ObjectMapper objectMapper;

    @BeforeAll
    static void setUp() throws Exception {
        objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        SchemaRegistry registry =
                SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        try (InputStream in =
                ScrapeResultSchemaTest.class.getResourceAsStream("/coins_catalog.schema.json")) {
            String schemaText = new String(Objects.requireNonNull(in).readAllBytes(), StandardCharsets.UTF_8);
            schema = registry.getSchema(schemaText, InputFormat.JSON);
        }
    }

    @Test
    void okResultPassesSchema() throws Exception {
        var request = ScrapeRequest.of("золото", true, null);
        var coin = new Coin("5216-0060", "Георгий Победоносец", "Золото", 7.78, 89500.0, 99700.0);
        var result = ScrapeResult.ok(request, 1, List.of(coin));

        assertThat(validate(result)).isEmpty();
    }

    @Test
    void errorResultPassesSchema() throws Exception {
        var request = ScrapeRequest.of(null, null, null);
        var result = ScrapeResult.error(request, "test failure");

        assertThat(validate(result)).isEmpty();
    }

    @Test
    void captchaBlockedPassesSchema() throws Exception {
        var request = ScrapeRequest.of("победоносец", true, null);
        var result = ScrapeResult.captchaBlocked(request, "CAPTCHA detected");

        assertThat(validate(result)).isEmpty();
    }

    @Test
    void notImplementedPassesSchema() throws Exception {
        var request = ScrapeRequest.of(null, true, null);
        var result = ScrapeResult.notImplemented(request);

        assertThat(validate(result)).isEmpty();
    }

    private List<Error> validate(ScrapeResult result) throws Exception {
        String json = objectMapper.writeValueAsString(result);
        return schema.validate(json, InputFormat.JSON);
    }
}
