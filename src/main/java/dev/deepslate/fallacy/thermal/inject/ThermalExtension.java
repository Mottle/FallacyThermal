package dev.deepslate.fallacy.thermal.inject;

import dev.deepslate.fallacy.thermal.ThermodynamicsEngine;

public interface ThermalExtension {
    default ThermodynamicsEngine fallacy$getThermalEngine() {
        throw new UnsupportedOperationException("This will never happen.");
    }

    default void fallacy$setThermalEngine(ThermodynamicsEngine engine) {
        throw new UnsupportedOperationException("This will never happen.");
    }
}
