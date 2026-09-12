package net.fly.insulin.mixin.compat;

import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.ModelQuadView;
import net.fly.insulin.backend.IrisCanonicalVertexData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
    targets = {
        "me.jellysquid.mods.sodium.client.model.light.flat.FlatLightPipeline",
        "me.jellysquid.mods.sodium.client.model.light.smooth.SmoothLightPipeline"
    },
    remap = false
)
public class MixinCanonicalDirectionalLighting {

    @Inject(method = "calculate", at = @At("HEAD"), remap = false)
    private void insulin$captureDirectionalLighting(
        ModelQuadView quad,
        BlockPos position,
        QuadLightData output,
        Direction cullFace,
        Direction lightFace,
        boolean shade,
        CallbackInfo callback
    ) {
        IrisCanonicalVertexData.setLighting(lightFace, shade);
    }
}
