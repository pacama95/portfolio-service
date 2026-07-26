package com.portfolio.adapters.outgoing.marketdata.eodhd;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Set;

public class EodhdDecimalDeserializer extends JsonDeserializer<BigDecimal> {

    private static final Set<String> MISSING_MARKERS = Set.of("", "NA", "N/A", "NULL");

    @Override
    public BigDecimal deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() == JsonToken.VALUE_NULL) {
            return null;
        }
        if (parser.currentToken().isNumeric()) {
            return parser.getDecimalValue();
        }
        if (parser.currentToken() == JsonToken.VALUE_STRING) {
            String raw = parser.getText().trim();
            if (MISSING_MARKERS.contains(raw.toUpperCase(Locale.ROOT))) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException failure) {
                throw InvalidFormatException.from(parser, "Expected an EODHD decimal value", raw, BigDecimal.class);
            }
        }
        return (BigDecimal) context.handleUnexpectedToken(BigDecimal.class, parser);
    }
}
