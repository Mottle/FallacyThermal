package dev.deepslate.fallacy.thermal.inject;

import dev.deepslate.fallacy.thermal.ThermodynamicsEngine;

public interface ThermalExtension {
    default ThermodynamicsEngine fallacy$getThermalEngine() {
        return null;
    }

    default void fallacy$setThermalEngine(ThermodynamicsEngine engine) {
        throw new UnsupportedOperationException("This will never happen.");
    }
}
