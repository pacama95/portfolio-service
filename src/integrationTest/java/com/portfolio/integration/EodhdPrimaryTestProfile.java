package com.portfolio.integration;

import java.util.HashMap;
import java.util.Map;

/** Same environment as {@link IntegrationTestProfile}, but with the provider order reversed. */
public class EodhdPrimaryTestProfile extends IntegrationTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> overrides = new HashMap<>(super.getConfigOverrides());
        overrides.put("application.market-data.providers", "eodhd,twelvedata");
        return overrides;
    }
}
