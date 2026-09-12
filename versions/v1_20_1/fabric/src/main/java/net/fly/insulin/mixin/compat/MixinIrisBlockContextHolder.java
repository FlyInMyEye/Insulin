package net.fly.insulin.mixin.compat;

import net.fly.insulin.backend.IrisCanonicalVertexData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.impl.block_context.BlockContextHolder", remap = false)
public class MixinIrisBlockContextHolder {

    @Shadow(remap = false) public short blockId;

    @Inject(method = "set", at = @At("RETURN"), remap = false, require = 0)
    private void insulin$storeCanonicalBlockState(
        BlockState state,
        short renderType,
        byte lightValue,
        CallbackInfo callback
    ) {
        int stateId = Block.getId(state);
        IrisCanonicalVertexData.setBlockStateId(stateId);
        this.blockId = (short) stateId;
    }

    @Inject(method = "reset", at = @At("RETURN"), remap = false, require = 0)
    private void insulin$resetCanonicalBlockState(CallbackInfo callback) {
        IrisCanonicalVertexData.reset();
    }
}
