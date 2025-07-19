package dev.deepslate.fallacy.thermal.mixin.thermal4block;

import dev.deepslate.fallacy.thermal.ThermodynamicsEngine;
import dev.deepslate.fallacy.thermal.block.BlockWithHeat;
import dev.deepslate.fallacy.thermal.block.BlockWithThermalConductivity;
import dev.deepslate.fallacy.thermal.inject.BlockWithThermal;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Block.class)
public abstract class BlockMixin implements BlockWithThermal {

    @Unique
    private Block fallacy$self() {
        return (Block) (Object) this;
    }

    @Unique
    private TriGetter<Integer> fallacy$intrinsicHeatGetter;

    @Unique
    private TriGetter<Integer> fallacy$epitaxialHeatGetter;

    @Unique
    private TriGetter<Float> fallacy$thermalConductivityGetter;

    @Override
    public void fallacy$setIntrinsicHeatGetter(@NotNull TriGetter<Integer> getter) {
        if (fallacy$self() instanceof BlockWithHeat) throw new RuntimeException("BlockWithHeat already exists");
        this.fallacy$intrinsicHeatGetter = getter;
    }

    @Override
    public void fallacy$setEpitaxialHeatGetter(@NotNull TriGetter<Integer> getter) {
        if (fallacy$self() instanceof BlockWithHeat) throw new RuntimeException("BlockWithHeat already exists");
        this.fallacy$epitaxialHeatGetter = getter;
    }

    @Override
    public void fallacy$setThermalConductivityGetter(@NotNull TriGetter<Float> getter) {
        if (fallacy$self() instanceof BlockWithThermalConductivity)
            throw new RuntimeException("BlockWithThermalConductivity already exists");
        this.fallacy$thermalConductivityGetter = getter;
    }

    @Override
    public int fallacy$getIntrinsicHeat(BlockState state, Level level, BlockPos pos) {
        if (state.getBlock() instanceof BlockWithHeat)
            return ((BlockWithHeat) state.getBlock()).getIntrinsicHeat(state, level, pos);
        if (fallacy$intrinsicHeatGetter == null) return 0;
        return fallacy$intrinsicHeatGetter.apply(state, level, pos);
    }

    @Override
    public int fallacy$getEpitaxialHeat(BlockState state, Level level, BlockPos pos) {
        if (state.getBlock() instanceof BlockWithHeat)
            return ((BlockWithHeat) state.getBlock()).getEpitaxialHeat(state, level, pos);
        if (fallacy$epitaxialHeatGetter == null) return fallacy$getIntrinsicHeat(state, level, pos);
        return fallacy$epitaxialHeatGetter.apply(state, level, pos);
    }

    @Override
    public float fallacy$getThermalConductivity(BlockState state, Level level, BlockPos pos) {
        if (state.getBlock() instanceof BlockWithThermalConductivity)
            return ((BlockWithThermalConductivity) state.getBlock()).getThermalConductivity(state, level, pos);
        if (fallacy$thermalConductivityGetter == null) return ThermodynamicsEngine.DEFAULT_THERMAL_CONDUCTIVITY;
        return fallacy$thermalConductivityGetter.apply(state, level, pos);
    }

    @Override
    public boolean fallacy$isHeatSource() {
        return fallacy$intrinsicHeatGetter != null || fallacy$self() instanceof BlockWithHeat;
    }
}
