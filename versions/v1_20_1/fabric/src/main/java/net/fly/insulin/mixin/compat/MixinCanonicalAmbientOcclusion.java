package net.fly.insulin.mixin.compat;

import me.jellysquid.mods.sodium.client.model.light.data.LightDataAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = LightDataAccess.class, remap = false)
public class MixinCanonicalAmbientOcclusion {

    @Redirect(
        method = "compute",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/class_2680;method_26210"
                + "(Lnet/minecraft/class_1922;Lnet/minecraft/class_2338;)F",
            remap = false
        ),
        remap = false
    )
    private float insulin$useCanonicalAmbientOcclusion(
        BlockState state,
        BlockGetter level,
        BlockPos position
    ) {
        return state.getBlock().getShadeBrightness(state, level, position);
    }
}
