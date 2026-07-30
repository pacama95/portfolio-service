package com.portfolio.integration;

import java.util.HashMap;
import java.util.Map;

/**
 * Same environment as {@link IntegrationTestProfile}, but TwelveData is the only provider —
 * the documented rollback configuration ({@code MARKET_DATA_PROVIDERS=twelvedata}).
 */
public class TwelveDataOnlyTestProfile extends IntegrationTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("application.market-data.providers", "twelvedata");
        return overrides;
    }
}
