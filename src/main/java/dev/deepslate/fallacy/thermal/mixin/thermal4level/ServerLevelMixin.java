package dev.deepslate.fallacy.thermal.mixin.thermal4level;

import dev.deepslate.fallacy.thermal.ThermodynamicsEngine;
import dev.deepslate.fallacy.thermal.impl.EnvironmentThermodynamicsEngine;
import dev.deepslate.fallacy.thermal.inject.ThermalExtension;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.RandomSequences;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.concurrent.Executor;

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
        return fallacy$thermodynamicsEngine;
    }

    @Override
    public void fallacy$setThermalEngine(ThermodynamicsEngine engine) {
        fallacy$thermodynamicsEngine = engine;
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    void construct(MinecraftServer server, Executor dispatcher, LevelStorageSource.LevelStorageAccess levelStorageAccess, ServerLevelData serverLevelData, ResourceKey dimension, LevelStem levelStem, ChunkProgressListener progressListener, boolean isDebug, long biomeZoomSeed, List customSpawners, boolean tickTime, RandomSequences randomSequences, CallbackInfo ci) {
        fallacy$thermodynamicsEngine = new EnvironmentThermodynamicsEngine(fallacy$self());
    }
}
