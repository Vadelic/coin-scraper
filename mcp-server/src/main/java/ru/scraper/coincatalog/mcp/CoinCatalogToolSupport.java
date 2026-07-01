package ru.scraper.coincatalog.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.scraper.coincatalog.model.ScrapeResult;

@Component
public class CoinCatalogToolSupport {

    private final ObjectMapper objectMapper;

    public CoinCatalogToolSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String toJson(ScrapeResult result) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize scrape result", e);
        }
    }
}
