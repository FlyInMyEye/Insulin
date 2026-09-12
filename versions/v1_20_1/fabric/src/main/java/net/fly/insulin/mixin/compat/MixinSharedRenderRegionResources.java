package net.fly.insulin.mixin.compat;

import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import net.fly.insulin.backend.SodiumMeshRestorer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = RenderRegion.DeviceResources.class, remap = false)
public class MixinSharedRenderRegionResources {

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gl/arena/GlBufferArena;<init>"
                + "(Lme/jellysquid/mods/sodium/client/gl/device/CommandList;II"
                + "Lme/jellysquid/mods/sodium/client/gl/arena/staging/StagingBuffer;)V"
        ),
        index = 2,
        remap = false
    )
    private int insulin$useSharedVertexStride(int original) {
        return SodiumMeshRestorer.sharedVertexStride(original);
    }
}
