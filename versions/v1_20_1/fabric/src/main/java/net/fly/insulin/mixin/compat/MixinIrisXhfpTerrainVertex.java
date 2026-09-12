package net.fly.insulin.mixin.compat;

import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.fly.insulin.backend.IrisCanonicalVertexData;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.sodium.impl.vertex_format.terrain_xhfp.XHFPTerrainVertex", remap = false)
public class MixinIrisXhfpTerrainVertex {

    @Inject(method = "write", at = @At("RETURN"), remap = false, require = 0)
    private void insulin$storeCanonicalBlockStateHighBits(
        long pointer,
        Material material,
        ChunkVertexEncoder.Vertex[] vertices,
        int section,
        CallbackInfoReturnable<Long> callback
    ) {
        byte metadata = IrisCanonicalVertexData.metadata();
        for (int vertex = 0; vertex < vertices.length; vertex++) {
            MemoryUtil.memPutByte(
                pointer + (long) vertex * IrisCanonicalVertexData.STRIDE
                    + IrisCanonicalVertexData.NORMAL_PADDING_OFFSET,
                metadata
            );
        }
    }
}
