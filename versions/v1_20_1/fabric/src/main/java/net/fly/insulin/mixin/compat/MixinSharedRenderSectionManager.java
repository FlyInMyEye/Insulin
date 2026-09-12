package net.fly.insulin.mixin.compat;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.fly.insulin.backend.SodiumMeshRestorer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(value = RenderSectionManager.class, remap = false)
public class MixinSharedRenderSectionManager {

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/DefaultChunkRenderer;<init>"
                + "(Lme/jellysquid/mods/sodium/client/gl/device/RenderDevice;"
                + "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"
        ),
        index = 1,
        remap = false
    )
    private ChunkVertexType insulin$useSharedRendererFormat(ChunkVertexType original) {
        return SodiumMeshRestorer.sharedVertexType(original);
    }

    @ModifyArg(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/compile/executor/ChunkBuilder;<init>"
                            + "(Lnet/minecraft/class_638;"
                + "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V"
        ),
        index = 1,
        remap = false
    )
    private ChunkVertexType insulin$useSharedBuilderFormat(ChunkVertexType original) {
        return SodiumMeshRestorer.sharedVertexType(original);
    }
}
