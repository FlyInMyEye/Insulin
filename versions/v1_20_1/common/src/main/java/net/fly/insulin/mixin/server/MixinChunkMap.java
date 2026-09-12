package net.fly.insulin.mixin.server;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.fly.insulin.InsulinCommon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import org.apache.commons.lang3.mutable.MutableObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class MixinChunkMap {

    @Inject(method = "readChunk", at = @At("HEAD"))
    private void insulin$beginDiskRead(ChunkPos chunkPos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        InsulinCommon.serverStats().beginDiskRead(chunkPos.toLong());
    }

    @Inject(method = "readChunk", at = @At("RETURN"))
    private void insulin$finishDiskRead(ChunkPos chunkPos, CallbackInfoReturnable<CompletableFuture<Optional<CompoundTag>>> callback) {
        CompletableFuture<Optional<CompoundTag>> future = callback.getReturnValue();
        future.whenComplete((result, throwable) -> InsulinCommon.serverStats().finishDiskRead(chunkPos.toLong()));
    }

    @Inject(method = "scheduleChunkLoad", at = @At("HEAD"))
    private void insulin$beginLoad(ChunkPos chunkPos, CallbackInfoReturnable<?> callback) {
        InsulinCommon.serverStats().beginLoad(chunkPos.toLong());
    }

    @Inject(method = "scheduleChunkLoad", at = @At("RETURN"))
    private void insulin$finishLoad(ChunkPos chunkPos, CallbackInfoReturnable<CompletableFuture<?>> callback) {
        CompletableFuture<?> future = callback.getReturnValue();
        future.whenComplete((result, throwable) -> InsulinCommon.serverStats().finishLoad(chunkPos.toLong()));
    }

    @Inject(method = "playerLoadedChunk", at = @At("HEAD"))
    private void insulin$beginPacket(
        ServerPlayer player,
        MutableObject<ClientboundLevelChunkWithLightPacket> packet,
        LevelChunk chunk,
        CallbackInfo callback
    ) {
        InsulinCommon.serverStats().beginPacketBuild(chunk.getPos().toLong());
    }

    @Inject(method = "playerLoadedChunk", at = @At("RETURN"))
    private void insulin$finishPacket(
        ServerPlayer player,
        MutableObject<ClientboundLevelChunkWithLightPacket> packet,
        LevelChunk chunk,
        CallbackInfo callback
    ) {
        InsulinCommon.serverStats().finishSend(chunk.getPos().toLong());
    }
}
