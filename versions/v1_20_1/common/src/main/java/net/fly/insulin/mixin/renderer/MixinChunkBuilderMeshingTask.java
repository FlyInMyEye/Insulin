package net.fly.insulin.mixin.renderer;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.util.task.CancellationToken;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.fly.insulin.InsulinCommon;
import net.fly.insulin.cache.AnimatedSpriteArtifact;
import net.fly.insulin.cache.CapturedPassArtifact;
import net.fly.insulin.cache.DiskArtifactStore;
import net.fly.insulin.cache.PassId;
import net.fly.insulin.cache.SectionArtifact;
import net.fly.insulin.cache.SectionArtifactKey;
import net.fly.insulin.cache.SectionFingerprint;
import net.fly.insulin.cache.StoreResult;
import net.fly.insulin.diagnostics.CapturedSectionSummary;
import net.fly.insulin.diagnostics.MeshStats;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public abstract class MixinChunkBuilderMeshingTask {

    @Shadow(remap = false) @Final private RenderSection render;

    @Shadow(remap = false) @Final private ChunkRenderContext renderContext;

    @Shadow(remap = false) @Final private int buildTime;

    @Unique
    private SectionFingerprint insulin$fingerprint;

    @Unique
    private SectionArtifact insulin$candidate;

    @Unique
    private boolean insulin$restored;

    @Unique
    private long insulin$generation;

    @Unique
    private MeshStats.Timing insulin$timing;

    @Unique
    private SectionArtifactKey insulin$pipelineKey;

    @Inject(
        method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
            + "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)"
            + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void insulin$findCandidate(
        ChunkBuildContext context,
        CancellationToken cancellationToken,
        CallbackInfoReturnable<ChunkBuildOutput> callback
    ) {
        this.insulin$restored = false;
        this.insulin$timing = InsulinCommon.stats().begin();
        this.insulin$pipelineKey = InsulinCommon.pipeline().beginSection(
            this.render.getChunkX(),
            this.render.getChunkY(),
            this.render.getChunkZ()
        );
        this.insulin$generation = InsulinCommon.artifacts().generation();
        this.insulin$fingerprint = SectionFingerprint.compute(this.renderContext);
        SectionArtifactKey key = new SectionArtifactKey(
            this.render.getChunkX(),
            this.render.getChunkY(),
            this.render.getChunkZ()
        );
        this.insulin$candidate = this.insulin$fingerprint == null ? null : InsulinCommon.artifacts().get(
            key,
            this.insulin$fingerprint,
            this.insulin$generation
        );
        boolean diskHit = false;
        if (this.insulin$candidate == null && this.insulin$fingerprint != null) {
            DiskArtifactStore disk = InsulinCommon.disk();
            if (disk != null) {
                this.insulin$candidate = disk.get(key, this.insulin$fingerprint);
                if (this.insulin$candidate != null) {
                    diskHit = true;
                    InsulinCommon.artifacts().put(key, this.insulin$candidate, this.insulin$generation);
                }
            }
        }
        if (this.insulin$candidate != null) {
            InsulinCommon.metrics().recordCandidate(diskHit);
            MeshStats.LookupResult lookupResult = diskHit
                ? MeshStats.LookupResult.DISK_HIT
                : MeshStats.LookupResult.RAM_HIT;
            InsulinCommon.stats().recordLookup(
                this.insulin$timing,
                lookupResult
            );
            if (!cancellationToken.isCancelled()) {
                ChunkBuildOutput restored = InsulinCommon.restorer().restore(
                    this.render,
                    this.buildTime,
                    this.insulin$candidate
                );
                restored = InsulinCommon.restorer().attachLiveBlockEntities(restored, this.renderContext);
                this.insulin$restored = true;
                InsulinCommon.stats().finishSkipped(this.insulin$timing, lookupResult);
                InsulinCommon.pipeline().finishSection(this.insulin$pipelineKey);
                InsulinCommon.metrics().recordRestoration(this.insulin$candidate.retainedBytes());
                callback.setReturnValue(restored);
            }
        } else {
            InsulinCommon.stats().recordLookup(this.insulin$timing, MeshStats.LookupResult.MISS);
        }
    }

    @Inject(
        method = "execute(Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildContext;"
            + "Lme/jellysquid/mods/sodium/client/util/task/CancellationToken;)"
            + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
        at = @At("RETURN"),
        remap = false
    )
    private void insulin$captureCompletedMesh(
        ChunkBuildContext context,
        CancellationToken cancellationToken,
        CallbackInfoReturnable<ChunkBuildOutput> callback
    ) {
        ChunkBuildOutput output = callback.getReturnValue();
        if (this.insulin$restored) {
            return;
        }
        InsulinCommon.stats().finishMeshing(this.insulin$timing);
        InsulinCommon.pipeline().finishSection(this.insulin$pipelineKey);
        if (output == null) {
            return;
        }

        long vertexBytes = 0;
        long vertexCount = 0;
        long copiedBytes = 0;
        int translucentPasses = 0;
        Map<PassId, CapturedPassArtifact> capturedPasses = new EnumMap<>(PassId.class);

        for (Map.Entry<TerrainRenderPass, BuiltSectionMeshParts> entry : output.meshes.entrySet()) {
            BuiltSectionMeshParts mesh = entry.getValue();
            vertexBytes += mesh.getVertexData().getLength();
            for (VertexRange range : mesh.getVertexRanges()) {
                if (range != null) {
                    vertexCount += range.vertexCount();
                }
            }

            PassId passId;
            if (entry.getKey() == DefaultTerrainRenderPasses.SOLID) {
                passId = PassId.SOLID;
            } else if (entry.getKey() == DefaultTerrainRenderPasses.CUTOUT) {
                passId = PassId.CUTOUT;
            } else if (entry.getKey() == DefaultTerrainRenderPasses.TRANSLUCENT
                && InsulinCommon.restorer().supportsTranslucentCaching()) {
                passId = PassId.TRANSLUCENT;
            } else {
                translucentPasses++;
                continue;
            }

            CapturedPassArtifact captured = InsulinCommon.restorer().capture(mesh);
            copiedBytes += captured.retainedBytes();
            capturedPasses.put(passId, captured);
        }

        List<AnimatedSpriteArtifact> animatedSprites = insulin$copyAnimatedSprites(output.info.animatedSprites);

        boolean cacheable = this.insulin$fingerprint != null
            && translucentPasses == 0;

        if (this.insulin$fingerprint == null) {
            InsulinCommon.stats().recordBypass(this.insulin$timing, "fingerprint");
        }
        if (translucentPasses != 0) {
            InsulinCommon.stats().recordBypass(this.insulin$timing, "translucent");
        }
        if (this.insulin$candidate != null) {
            InsulinCommon.metrics().recordVerification(cacheable && insulin$matches(this.insulin$candidate, output));
        }

        StoreResult storeResult = null;
        if (cacheable) {
            SectionArtifact artifact = new SectionArtifact(
                output.buildTime,
                this.insulin$fingerprint,
                InsulinCommon.restorer().stateFingerprint(),
                output.info.visibilityData,
                capturedPasses,
                animatedSprites
            );
            storeResult = InsulinCommon.artifacts().put(new SectionArtifactKey(
                output.render.getChunkX(),
                output.render.getChunkY(),
                output.render.getChunkZ()
            ), artifact, this.insulin$generation);
            if (storeResult.outcome() != StoreResult.Outcome.REJECTED_STALE) {
                DiskArtifactStore disk = InsulinCommon.disk();
                if (disk != null) {
                    disk.put(new SectionArtifactKey(
                        output.render.getChunkX(),
                        output.render.getChunkY(),
                        output.render.getChunkZ()
                    ), artifact);
                }
            }
        }

        InsulinCommon.metrics().record(new CapturedSectionSummary(
            output.render.getChunkX(),
            output.render.getChunkY(),
            output.render.getChunkZ(),
            output.buildTime,
            output.meshes.size(),
            vertexCount,
            vertexBytes
        ), storeResult, copiedBytes, translucentPasses);
        InsulinCommon.restorer().prepareLiveOutput(output);
    }

    private static List<AnimatedSpriteArtifact> insulin$copyAnimatedSprites(TextureAtlasSprite[] sprites) {
        if (sprites == null || sprites.length == 0) {
            return List.of();
        }
        List<AnimatedSpriteArtifact> copies = new ArrayList<>(sprites.length);
        for (TextureAtlasSprite sprite : sprites) {
            copies.add(new AnimatedSpriteArtifact(sprite.atlasLocation().toString(), sprite.contents().name().toString()));
        }
        return copies;
    }

    private static boolean insulin$matches(SectionArtifact candidate, ChunkBuildOutput output) {
        if (candidate.visibilityData() != output.info.visibilityData
            || candidate.passes().size() != output.meshes.size()) {
            return false;
        }

        for (Map.Entry<PassId, CapturedPassArtifact> entry : candidate.passes().entrySet()) {
            TerrainRenderPass pass = switch (entry.getKey()) {
                case SOLID -> DefaultTerrainRenderPasses.SOLID;
                case CUTOUT -> DefaultTerrainRenderPasses.CUTOUT;
                case TRANSLUCENT -> DefaultTerrainRenderPasses.TRANSLUCENT;
            };
            BuiltSectionMeshParts mesh = output.meshes.get(pass);
            if (mesh == null || !InsulinCommon.restorer().matches(entry.getValue(), mesh)) {
                return false;
            }
        }
        return true;
    }
}
