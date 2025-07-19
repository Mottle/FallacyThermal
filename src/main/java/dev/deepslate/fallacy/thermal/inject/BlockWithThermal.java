package dev.deepslate.fallacy.thermal.inject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.NotNull;

public interface BlockWithThermal {
    default void fallacy$setIntrinsicHeatGetter(@NotNull TriGetter<Integer> getter) {
        throw new UnsupportedOperationException("This will not happen");
    }

    default void fallacy$setEpitaxialHeatGetter(@NotNull TriGetter<Integer> getter) {
        throw new UnsupportedOperationException("This will not happen");
    }

    default void fallacy$setThermalConductivityGetter(@NotNull TriGetter<Float> getter) {
        throw new UnsupportedOperationException("This will not happen");
    }

    default int fallacy$getIntrinsicHeat(BlockState state, Level level, BlockPos pos) {
        throw new UnsupportedOperationException("This will not happen");
    }

    default int fallacy$getEpitaxialHeat(BlockState state, Level level, BlockPos pos) {
        throw new UnsupportedOperationException("This will not happen");
    }

    default float fallacy$getThermalConductivity(BlockState state, Level level, BlockPos pos) {
        throw new UnsupportedOperationException("This will not happen");
    }

    default boolean fallacy$isHeatSource() {
        throw new UnsupportedOperationException("This will not happen");
    }

    @FunctionalInterface
    interface TriGetter <T> {
        @NotNull
        T apply(@NotNull BlockState state, @NotNull Level level, @NotNull BlockPos pos);
    }
}
