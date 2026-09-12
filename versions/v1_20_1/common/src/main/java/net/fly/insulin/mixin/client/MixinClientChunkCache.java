package net.fly.insulin.mixin.client;

import java.util.function.Consumer;
import net.fly.insulin.InsulinCommon;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientChunkCache.class)
public abstract class MixinClientChunkCache {

    @Inject(method = "replaceWithPacketData", at = @At("HEAD"))
    private void insulin$beginPacketApply(
        int chunkX,
        int chunkZ,
        FriendlyByteBuf buffer,
        CompoundTag tag,
        Consumer<?> consumer,
        CallbackInfoReturnable<LevelChunk> callback
    ) {
        InsulinCommon.pipeline().beginPacket(chunkX, chunkZ);
    }

    @Inject(method = "replaceWithPacketData", at = @At("RETURN"))
    private void insulin$finishPacketApply(
        int chunkX,
        int chunkZ,
        FriendlyByteBuf buffer,
        CompoundTag tag,
        Consumer<?> consumer,
        CallbackInfoReturnable<LevelChunk> callback
    ) {
        InsulinCommon.pipeline().finishPacket(chunkX, chunkZ);
    }
}
