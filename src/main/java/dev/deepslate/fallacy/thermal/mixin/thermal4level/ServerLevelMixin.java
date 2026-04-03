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
    protected volatile ThermodynamicsEngine fallacy$thermodynamicsEngine = null;

    @Override
    public ThermodynamicsEngine fallacy$getThermalEngine() {
        ThermodynamicsEngine engine = fallacy$thermodynamicsEngine;
        if (engine == null) {
            synchronized (this) {
                engine = fallacy$thermodynamicsEngine;
                if (engine == null) {
                    engine = new EnvironmentThermodynamicsEngine(fallacy$self());
                    fallacy$thermodynamicsEngine = engine;
                }
            }
        }
        return engine;
    }

    @Override
    public void fallacy$setThermalEngine(ThermodynamicsEngine engine) {
        fallacy$thermodynamicsEngine = engine;
    }
}
