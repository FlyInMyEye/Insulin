package net.fly.insulin.mixin.renderer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Future;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkUpdateType;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSectionManager;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.compile.executor.ChunkJobResult;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegion;
import me.jellysquid.mods.sodium.client.render.chunk.region.RenderRegionManager;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.fly.insulin.InsulinCommon;
import net.fly.insulin.cache.DiskArtifactStore;
import net.fly.insulin.cache.SectionArtifactKey;
import net.fly.insulin.renderer.DoppelgangerCuller;
import net.fly.insulin.renderer.DoppelgangerSection;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class MixinRenderSectionManager {

    @Unique
    private static final int INSULIN_MIN_DOPPELGANGERS_PER_FRAME = 4;

    @Unique
    private static final int INSULIN_MAX_DOPPELGANGERS_PER_FRAME = 130;

    @Unique
    private static final long INSULIN_LIGHT_UPLOAD_BYTES = 8L * 1024L * 1024L;

    @Unique
    private static final long INSULIN_TARGET_UPLOAD_NANOS = 2_000_000L;

    @Unique
    private static final int INSULIN_PRIORITY_BACKLOG_CAPACITY = 256;

    @Unique
    private static final float INSULIN_SECTION_FRUSTUM_RADIUS = 9.125F;

    @Shadow @Final private Long2ReferenceMap<RenderSection> sectionByPosition;

    @Shadow @Final private RenderRegionManager regions;

    @Shadow @Final private OcclusionCuller occlusionCuller;

    @Shadow @Final private ClientLevel world;

    @Shadow @Final private int renderDistance;

    @Shadow private int lastUpdatedFrame;

    @Shadow private boolean needsUpdate;

    @Shadow protected abstract void connectNeighborNodes(RenderSection section);

    @Shadow public abstract void onSectionRemoved(int x, int y, int z);

    @Shadow @Final private ConcurrentLinkedDeque<ChunkJobResult<ChunkBuildOutput>> buildResults;

    @Unique
    private final BlockingQueue<DiskArtifactStore.PrefetchedArtifact> insulin$prefetched = new ArrayBlockingQueue<>(256);

    @Unique
    private final Map<RenderSection, DoppelState> insulin$speculativeSections = new IdentityHashMap<>();

    @Unique
    private Future<?> insulin$prefetch;

    @Unique
    private final ArrayDeque<DiskArtifactStore.PrefetchedArtifact> insulin$priorityBacklog = new ArrayDeque<>();

    @Unique
    private Viewport insulin$viewport;

    @Unique
    private int insulin$centerX = Integer.MIN_VALUE;

    @Unique
    private int insulin$centerZ = Integer.MIN_VALUE;

    @Unique
    private long insulin$namespaceGeneration = Long.MIN_VALUE;

    @Unique
    private float insulin$prefetchViewX;

    @Unique
    private float insulin$prefetchViewY;

    @Unique
    private float insulin$prefetchViewZ;

    @Unique
    private boolean insulin$prefetchOcclusionCulling;

    @Unique
    private int insulin$adaptiveUploadLimit = 7;

    @Unique
    private double insulin$uploadNanosPerSection = INSULIN_TARGET_UPLOAD_NANOS / 7.0D;

    @Unique
    private long insulin$processStartedNanos;

    @Unique
    private int insulin$processOutputCount;

    @Inject(method = "onSectionAdded", at = @At("HEAD"), remap = false)
    private void insulin$validateSpeculativeSection(int x, int y, int z, CallbackInfo callback) {
        RenderSection section = this.sectionByPosition.get(SectionPos.asLong(x, y, z));
        DoppelState state = section == null ? null : this.insulin$speculativeSections.get(section);
        if (state != null && state.authoritativeNanos == 0L) {
            section.setPendingUpdate(ChunkUpdateType.REBUILD);
            this.needsUpdate = true;
        }
    }

    @Inject(method = "onSectionRemoved", at = @At("HEAD"), cancellable = true, remap = false)
    private void insulin$forgetSpeculativeSection(int x, int y, int z, CallbackInfo callback) {
        RenderSection section = this.sectionByPosition.get(SectionPos.asLong(x, y, z));
        DoppelState state = section == null ? null : this.insulin$speculativeSections.get(section);
        if (state != null && this.world.hasChunk(x, z)) {
            if (state.authoritativeNanos == 0L) {
                section.setPendingUpdate(ChunkUpdateType.REBUILD);
            }
            this.needsUpdate = true;
            callback.cancel();
            return;
        }
        if (state != null) {
            this.insulin$speculativeSections.remove(section);
            ((DoppelgangerSection) section).insulin$setDoppelganger(false);
            this.insulin$updateDoppelgangerCount();
        }
    }

    @Inject(method = "update", at = @At("HEAD"), remap = false)
    private void insulin$restoreNearbyCachedSections(
        Camera camera,
        Viewport viewport,
        int frame,
        boolean spectator,
        CallbackInfo callback
    ) {
        this.insulin$viewport = viewport;
        long generation = InsulinCommon.artifacts().generation();
        int centerX = SectionPos.blockToSectionCoord(camera.getBlockPosition().getX());
        int centerY = SectionPos.blockToSectionCoord(camera.getBlockPosition().getY());
        int centerZ = SectionPos.blockToSectionCoord(camera.getBlockPosition().getZ());
        var cameraPosition = camera.getPosition();
        var view = camera.getLookVector();
        boolean useOcclusionCulling = Minecraft.getInstance().smartCull;
        if (spectator && this.world.getBlockState(camera.getBlockPosition()).isSolidRender(
            this.world,
            camera.getBlockPosition()
        )) {
            useOcclusionCulling = false;
        }
        int restartDistance = Math.max(4, this.renderDistance / 2);
        float viewAlignment = view.x() * this.insulin$prefetchViewX
            + view.y() * this.insulin$prefetchViewY
            + view.z() * this.insulin$prefetchViewZ;
        if (generation != this.insulin$namespaceGeneration || this.insulin$centerX == Integer.MIN_VALUE
            || Math.abs(centerX - this.insulin$centerX) > restartDistance
            || Math.abs(centerZ - this.insulin$centerZ) > restartDistance
            || useOcclusionCulling != this.insulin$prefetchOcclusionCulling
            || viewAlignment < 0.5F) {
            this.insulin$namespaceGeneration = generation;
            this.insulin$centerX = centerX;
            this.insulin$centerZ = centerZ;
            this.insulin$prefetchViewX = view.x();
            this.insulin$prefetchViewY = view.y();
            this.insulin$prefetchViewZ = view.z();
            this.insulin$prefetchOcclusionCulling = useOcclusionCulling;
            if (this.insulin$prefetch != null) {
                this.insulin$prefetch.cancel(true);
            }
            this.insulin$clearPrefetched();
            DiskArtifactStore disk = InsulinCommon.disk();
            this.insulin$prefetch = disk == null ? null : disk.prefetchNearby(
                centerX,
                centerY,
                centerZ,
                cameraPosition.x,
                cameraPosition.y,
                cameraPosition.z,
                view.x(),
                view.y(),
                view.z(),
                this.renderDistance,
                this.world.getMinSection(),
                this.world.getMaxSection() - 1,
                useOcclusionCulling,
                viewport,
                InsulinCommon.restorer().previewStateFingerprints(),
                this.insulin$prefetched
            );
        }
        if (this.insulin$prefetch != null && this.insulin$prefetch.isDone() && this.insulin$prefetched.isEmpty()
            && this.insulin$priorityBacklog.isEmpty()) {
            this.insulin$prefetch = null;
        }
    }

    @Inject(method = "uploadChunks", at = @At("HEAD"), remap = false)
    private void insulin$queuePreparedDoppelgangers(CallbackInfo callback) {
        if (InsulinCommon.artifacts().generation() != this.insulin$namespaceGeneration) {
            return;
        }

        int countLimit = this.insulin$doppelgangerUploadBudget();
        long byteLimit = this.insulin$doppelgangerByteBudget(countLimit);
        long startedNanos = System.nanoTime();
        long deadlineNanos = startedNanos + this.insulin$doppelgangerCpuBudget(countLimit);
        long queuedBytes = 0L;
        int queued = 0;
        int examined = 0;
        int scanLimit = Math.max(32, countLimit * 4);
        boolean created = false;

        this.insulin$fillPriorityBacklog();

        while (queued < countLimit && examined < scanLimit) {
            if (examined >= INSULIN_MIN_DOPPELGANGERS_PER_FRAME && System.nanoTime() >= deadlineNanos) {
                break;
            }

            DiskArtifactStore.PrefetchedArtifact prefetched = this.insulin$takePrefetched();
            if (prefetched == null) {
                break;
            }
            examined++;

            long artifactBytes = prefetched.artifact().retainedBytes();
            if (queued > 0 && queuedBytes + artifactBytes > byteLimit) {
                this.insulin$priorityBacklog.addFirst(prefetched);
                break;
            }

            SectionArtifactKey key = prefetched.key();
            long position = SectionPos.asLong(key.sectionX(), key.sectionY(), key.sectionZ());
            RenderSection section = this.sectionByPosition.get(position);
            boolean newSection = section == null;
            if (!newSection && (section.isBuilt() || this.insulin$speculativeSections.containsKey(section))) {
                prefetched.free();
                continue;
            }
            if (newSection) {
                RenderRegion region = this.regions.createForChunk(key.sectionX(), key.sectionY(), key.sectionZ());
                section = new RenderSection(region, key.sectionX(), key.sectionY(), key.sectionZ());
                region.addSection(section);
                this.sectionByPosition.put(position, section);
                this.connectNeighborNodes(section);
            }
            DoppelgangerSection doppelganger = (DoppelgangerSection) section;
            doppelganger.insulin$setDoppelganger(true);
            doppelganger.insulin$setGraphFallback(true);
            ((DoppelgangerCuller) this.occlusionCuller).insulin$trackDoppelganger(section);
            this.insulin$speculativeSections.put(section, new DoppelState(
                this.insulin$restore(prefetched, section, newSection),
                System.nanoTime()
            ));
            queued++;
            queuedBytes += artifactBytes;
            created = true;
        }
        if (created) {
            this.needsUpdate = true;
        }
        this.insulin$updateDoppelgangerCount();
        if (this.insulin$prefetch != null && this.insulin$prefetch.isDone() && this.insulin$prefetched.isEmpty()
            && this.insulin$priorityBacklog.isEmpty()) {
            this.insulin$prefetch = null;
        }
    }

    @Unique
    private DiskArtifactStore.PrefetchedArtifact insulin$takePrefetched() {
        Iterator<DiskArtifactStore.PrefetchedArtifact> iterator = this.insulin$priorityBacklog.iterator();
        while (iterator.hasNext()) {
            DiskArtifactStore.PrefetchedArtifact prefetched = iterator.next();
            if (this.insulin$isVisiblePriority(prefetched.key())) {
                iterator.remove();
                return prefetched;
            }
        }
        return this.insulin$priorityBacklog.pollFirst();
    }

    @Unique
    private void insulin$fillPriorityBacklog() {
        while (this.insulin$priorityBacklog.size() < INSULIN_PRIORITY_BACKLOG_CAPACITY) {
            DiskArtifactStore.PrefetchedArtifact prefetched = this.insulin$prefetched.poll();
            if (prefetched == null) {
                break;
            }
            this.insulin$priorityBacklog.addLast(prefetched);
        }
    }

    @Unique
    private boolean insulin$isVisiblePriority(SectionArtifactKey key) {
        RenderSection section = this.sectionByPosition.get(
            SectionPos.asLong(key.sectionX(), key.sectionY(), key.sectionZ())
        );
        if (section != null
            && ((DoppelgangerSection) section).insulin$getVisibleFrame() == this.lastUpdatedFrame) {
            return true;
        }

        Viewport viewport = this.insulin$viewport;
        return viewport != null && viewport.isBoxVisible(
            (key.sectionX() << 4) + 8,
            (key.sectionY() << 4) + 8,
            (key.sectionZ() << 4) + 8,
            INSULIN_SECTION_FRUSTUM_RADIUS,
            INSULIN_SECTION_FRUSTUM_RADIUS,
            INSULIN_SECTION_FRUSTUM_RADIUS
        );
    }

    @Unique
    private void insulin$clearPrefetched() {
        DiskArtifactStore.PrefetchedArtifact prefetched;
        while ((prefetched = this.insulin$priorityBacklog.pollFirst()) != null) {
            prefetched.free();
        }
        while ((prefetched = this.insulin$prefetched.poll()) != null) {
            prefetched.free();
        }
    }

    @Unique
    private int insulin$doppelgangerUploadBudget() {
        int fps = Minecraft.getInstance().getFps();
        if (fps < 30) {
            return 4;
        }
        if (fps < 55) {
            return 7;
        }
        return this.insulin$adaptiveUploadLimit;
    }

    @Unique
    private long insulin$doppelgangerByteBudget(int countLimit) {
        if (countLimit <= 4) {
            return 512L * 1024L;
        }
        if (countLimit <= 7) {
            return 1024L * 1024L;
        }
        return INSULIN_LIGHT_UPLOAD_BYTES;
    }

    @Unique
    private long insulin$doppelgangerCpuBudget(int countLimit) {
        if (countLimit <= 4) {
            return 250_000L;
        }
        if (countLimit <= 7) {
            return 400_000L;
        }
        return 1_250_000L;
    }

    @Unique
    private int insulin$restore(
        DiskArtifactStore.PrefetchedArtifact prefetched,
        RenderSection section,
        boolean newSection
    ) {
        int buildTime = Math.max(this.lastUpdatedFrame - 1, section.getLastBuiltFrame());
        ChunkBuildOutput output = InsulinCommon.restorer().restorePrepared(
            section,
            buildTime,
            prefetched.artifact(),
            prefetched.prepared()
        );
        section.setInfo(output.info);
        section.setLastBuiltFrame(buildTime);
        this.buildResults.add(ChunkJobResult.successfully(output));
        InsulinCommon.doppelgangers().recordShown();
        if (newSection) {
            section.setPendingUpdate(null);
            section.setLastSubmittedFrame(buildTime);
        }
        return buildTime;
    }

    @Unique
    private void insulin$updateDoppelgangerCount() {
        InsulinCommon.doppelgangers().setRendered(this.insulin$speculativeSections.size());
        InsulinCommon.doppelgangers().setReady(
            this.insulin$priorityBacklog.size() + this.insulin$prefetched.size()
        );
    }

    @Inject(
        method = "createRebuildTask(Lme/jellysquid/mods/sodium/client/render/chunk/RenderSection;I)"
            + "Lme/jellysquid/mods/sodium/client/render/chunk/compile/tasks/ChunkBuilderMeshingTask;",
        at = @At("HEAD"),
        remap = false
    )
    private void insulin$submitRebuild(
        RenderSection render,
        int buildTime,
        CallbackInfoReturnable<?> callback
    ) {
        InsulinCommon.pipeline().submitSection(render.getChunkX(), render.getChunkY(), render.getChunkZ());
    }

    @Inject(
        method = "processChunkBuildResults(Ljava/util/ArrayList;)V",
        at = @At("HEAD"),
        remap = false
    )
    private void insulin$beginUploadFeedback(ArrayList<ChunkBuildOutput> outputs, CallbackInfo callback) {
        this.insulin$discardPrematureAuthoritativeResults(outputs);
        this.insulin$processStartedNanos = System.nanoTime();
        this.insulin$processOutputCount = outputs.size();
    }

    @Unique
    private void insulin$discardPrematureAuthoritativeResults(ArrayList<ChunkBuildOutput> outputs) {
        Iterator<ChunkBuildOutput> iterator = outputs.iterator();
        while (iterator.hasNext()) {
            ChunkBuildOutput output = iterator.next();
            DoppelState state = this.insulin$speculativeSections.get(output.render);
            if (state == null || output.buildTime <= state.buildTime) {
                continue;
            }

            if (output.buildTime < output.render.getLastSubmittedFrame()) {
                output.delete();
                iterator.remove();
                continue;
            }

            if (output.info == BuiltSectionInfo.EMPTY && !this.insulin$isWorldSectionEmpty(output.render)) {
                output.delete();
                iterator.remove();
                output.render.setPendingUpdate(ChunkUpdateType.REBUILD);
                this.needsUpdate = true;
            }
        }
    }

    @Unique
    private boolean insulin$isWorldSectionEmpty(RenderSection section) {
        LevelChunk chunk = this.world.getChunkSource().getChunk(
            section.getChunkX(),
            section.getChunkZ(),
            ChunkStatus.FULL,
            false
        );
        if (chunk == null) {
            return false;
        }

        int index = this.world.getSectionIndexFromSectionY(section.getChunkY());
        return index >= 0 && index < chunk.getSections().length && chunk.getSections()[index].hasOnlyAir();
    }

    @Inject(
        method = "processChunkBuildResults(Ljava/util/ArrayList;)V",
        at = @At("RETURN"),
        remap = false
    )
    private void insulin$finishSpeculativeSections(ArrayList<ChunkBuildOutput> outputs, CallbackInfo callback) {
        this.insulin$updateUploadFeedback(System.nanoTime() - this.insulin$processStartedNanos,
            this.insulin$processOutputCount);
        for (ChunkBuildOutput output : outputs) {
            DoppelState state = this.insulin$speculativeSections.get(output.render);
            if (state != null && state.authoritativeNanos == 0L && output.buildTime > state.buildTime
                && output.render.getLastBuiltFrame() == output.buildTime
                && output.buildTime >= output.render.getLastSubmittedFrame()
                && output.render.getPendingUpdate() == null
                && output.render.getBuildCancellationToken() == null) {
                state.authoritativeNanos = System.nanoTime();
                InsulinCommon.doppelgangers().recordAuthoritative(state.authoritativeNanos - state.shownNanos);
                this.insulin$speculativeSections.remove(output.render);
                ((DoppelgangerSection) output.render).insulin$setDoppelganger(false);
                this.insulin$updateDoppelgangerCount();
            }
        }
    }

    @Unique
    private void insulin$updateUploadFeedback(long elapsedNanos, int outputCount) {
        if (outputCount <= 0 || elapsedNanos <= 0L) {
            return;
        }

        double sample = elapsedNanos / (double) outputCount;
        this.insulin$uploadNanosPerSection = this.insulin$uploadNanosPerSection * 0.75D + sample * 0.25D;

        int target = (int) Math.max(
            INSULIN_MIN_DOPPELGANGERS_PER_FRAME,
            Math.min(INSULIN_MAX_DOPPELGANGERS_PER_FRAME,
                INSULIN_TARGET_UPLOAD_NANOS / this.insulin$uploadNanosPerSection)
        );
        if (elapsedNanos > 4_000_000L) {
            target = INSULIN_MIN_DOPPELGANGERS_PER_FRAME;
        } else if (elapsedNanos > 2_500_000L) {
            target = Math.min(target, 7);
        }

        if (target < this.insulin$adaptiveUploadLimit) {
            this.insulin$adaptiveUploadLimit = target;
        } else {
            this.insulin$adaptiveUploadLimit = Math.min(target, this.insulin$adaptiveUploadLimit + 8);
        }
    }

    @Inject(method = "destroy", at = @At("HEAD"), remap = false)
    private void insulin$discardPreparedDoppelgangers(CallbackInfo callback) {
        if (this.insulin$prefetch != null) {
            this.insulin$prefetch.cancel(true);
            this.insulin$prefetch = null;
        }
        this.insulin$clearPrefetched();
    }

    @Unique
    private static final class DoppelState {

        private final int buildTime;
        private final long shownNanos;
        private long authoritativeNanos;

        private DoppelState(int buildTime, long shownNanos) {
            this.buildTime = buildTime;
            this.shownNanos = shownNanos;
        }
    }
}
