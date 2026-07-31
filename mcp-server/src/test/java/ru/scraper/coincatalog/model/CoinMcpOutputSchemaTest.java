package ru.scraper.coincatalog.model;

import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson3.DefaultJsonSchemaValidator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MCP tools return {@code List<Coin>} with {@code generateOutputSchema=true}. Spring AI
 * builds the schema from the Java type; without {@code @Schema(nullable=true)} on Coin,
 * null prices fail server-side output validation.
 */
class CoinMcpOutputSchemaTest {

    private static JsonSchemaValidator validator;
    private static Map<String, Object> listSchema;
    private static JsonMapper objectMapper;

    @BeforeAll
    static void setUp() throws Exception {
        objectMapper = JsonMapper.builder().build();
        validator = new DefaultJsonSchemaValidator(objectMapper);
        Type listOfCoin = new ParameterizedType() {
            @Override
            public Type[] getActualTypeArguments() {
                return new Type[] {Coin.class};
            }

            @Override
            public Type getRawType() {
                return List.class;
            }

            @Override
            public Type getOwnerType() {
                return null;
            }
        };
        String schemaJson = McpJsonSchemaGenerator.generateFromType(listOfCoin);
        listSchema = objectMapper.readValue(schemaJson, new TypeReference<>() {});
        assertThat(validator.validateSchema(listSchema).valid()).isTrue();
    }

    @Test
    void generatedSchemaAllowsNullOnOptionalCoinFields() throws Exception {
        Map<String, Object> schema =
                objectMapper.readValue(McpJsonSchemaGenerator.generateFromClass(Coin.class), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(propertyTypes(properties, "buyPrice")).containsExactlyInAnyOrder("number", "null");
        assertThat(propertyTypes(properties, "sellPrice")).containsExactlyInAnyOrder("number", "null");
        assertThat(propertyTypes(properties, "weightG")).containsExactlyInAnyOrder("number", "null");
        assertThat(propertyTypes(properties, "catalogNumber")).containsExactlyInAnyOrder("string", "null");
        assertThat(propertyTypes(properties, "metal")).containsExactlyInAnyOrder("string", "null");
        assertThat(propertyTypes(properties, "name")).containsExactly("string");
    }

    @Test
    void listWithNullBuyPricePassesMcpOutputValidation() {
        var coins = List.of(
                new Coin("ax-1", "Георгий Победоносец", "Золото", 7.78, null, 99700.0),
                new Coin(null, "Серебряный слиток", null, null, null, null));

        Object instance = objectMapper.convertValue(coins, new TypeReference<List<Map<String, Object>>>() {});
        JsonSchemaValidator.ValidationResponse response = validator.validate(listSchema, instance);

        assertThat(response.valid())
                .as("validation errors: %s", response.errorMessage())
                .isTrue();
    }

    @SuppressWarnings("unchecked")
    private static List<String> propertyTypes(Map<String, Object> properties, String field) {
        Map<String, Object> property = (Map<String, Object>) properties.get(field);
        Object type = property.get("type");
        if (type instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        return List.of(String.valueOf(type));
    }
}
