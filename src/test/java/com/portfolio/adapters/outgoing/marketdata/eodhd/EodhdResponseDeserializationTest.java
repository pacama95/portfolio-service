package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EodhdResponseDeserializationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void givenOfficialExchangeShape_whenDeserialized_thenPreservesProviderCodes() throws Exception {
        String json = """
                [{
                  "Name": "USA Stocks",
                  "Code": "US",
                  "OperatingMIC": "XNAS,XNYS",
                  "Country": "USA",
                  "Currency": "USD",
                  "CountryISO2": "US",
                  "CountryISO3": "USA"
                }]
                """;

        List<EodhdExchangeResponse> result = objectMapper.readValue(json, new TypeReference<>() {});

        assertEquals("US", result.getFirst().code());
        assertEquals("XNAS,XNYS", result.getFirst().operatingMic());
    }

    @Test
    void givenNumbersNumericStringsAndMissingMarkers_whenDeserialized_thenUsesExactDecimalsOrNull()
            throws Exception {
        EodhdRealTimeResponse number = objectMapper.readValue("{\"close\":189.25}", EodhdRealTimeResponse.class);
        EodhdRealTimeResponse string = objectMapper.readValue("{\"close\":\"189.25\"}", EodhdRealTimeResponse.class);
        EodhdRealTimeResponse missing = objectMapper.readValue("{\"close\":\"NA\"}", EodhdRealTimeResponse.class);

        assertEquals(new BigDecimal("189.25"), number.close());
        assertEquals(new BigDecimal("189.25"), string.close());
        assertNull(missing.close());
    }

    @Test
    void givenInvalidDecimal_whenDeserialized_thenFailsInsteadOfCoercing() {
        assertThrows(
                Exception.class,
                () -> objectMapper.readValue("{\"close\":\"not-a-number\"}", EodhdRealTimeResponse.class));
    }
}
