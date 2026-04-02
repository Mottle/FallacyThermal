package dev.deepslate.fallacy.thermal.mixin.thermal4level;

import dev.deepslate.fallacy.thermal.ThermodynamicsEngine;
import dev.deepslate.fallacy.thermal.impl.EnvironmentThermodynamicsEngine;
import dev.deepslate.fallacy.thermal.inject.ThermalExtension;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin implements ThermalExtension {

    @Unique
    private ServerLevel fallacy$self() {
        return (ServerLevel) (Object) this;
    }

    @Unique
    protected ThermodynamicsEngine fallacy$thermodynamicsEngine = null;

    @Override
    public ThermodynamicsEngine fallacy$getThermalEngine() {
        if (fallacy$thermodynamicsEngine == null) {
            fallacy$thermodynamicsEngine = new EnvironmentThermodynamicsEngine(fallacy$self());
        }
        return fallacy$thermodynamicsEngine;
    }

    @Override
    public void fallacy$setThermalEngine(ThermodynamicsEngine engine) {
        fallacy$thermodynamicsEngine = engine;
    }
}
