package dev.deepslate.fallacy.thermal.mixin.thermal4level;

import dev.deepslate.fallacy.thermal.ThermodynamicsEngine;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {

    @Shadow
    public abstract Level getLevel();

    //锚定在setBlockState中，当调用时检查并进行热量更新计算
    @Inject(method = "setBlockState", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/lighting/LightEngine;hasDifferentLightProperties(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)Z"), locals = LocalCapture.CAPTURE_FAILSOFT)
    void injectSetBlockState(BlockPos pos, BlockState state, boolean isMoving, CallbackInfoReturnable<BlockState> cir, int i, LevelChunkSection levelchunksection, boolean flag, int j, int k, int l, BlockState blockstate, Block block, boolean flag1) {
        //state: newState, blockstate: oldState
        if (getLevel().isClientSide) return;
//        BlockStateHeatChangeRule.INSTANCE.rule(blockstate, state, getLevel(), pos);

        ServerLevel level = (ServerLevel) getLevel();

        if (!ThermodynamicsEngine.Companion.hasDifferentHeatProperties(blockstate, state, level, pos)) return;

        var engine = ThermodynamicsEngine.Companion.getEngine(level);
        engine.checkBlock(pos);
    }
}
