package net.fly.insulin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import net.fly.insulin.backend.BackendIdentity;
import net.fly.insulin.backend.BackendMeshRestorer;
import net.fly.insulin.cache.ArtifactStore;
import net.fly.insulin.cache.DiskArtifactStore;
import net.fly.insulin.diagnostics.InvalidationStats;
import net.fly.insulin.diagnostics.ChunkPipelineStats;
import net.fly.insulin.diagnostics.DoppelgangerStats;
import net.fly.insulin.diagnostics.MeshStats;
import net.fly.insulin.diagnostics.PrototypeMetrics;
import net.fly.insulin.diagnostics.ServerChunkStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class InsulinCommon {

    private static final long RAM_CACHE_BYTES = 512L * 1024L * 1024L;

    public static final String MOD_ID = "insulin";
    public static final String TARGET_VERSION = "1.20.1";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static final AtomicReference<BackendIdentity> BACKEND = new AtomicReference<>();
    private static final AtomicReference<BackendMeshRestorer> RESTORER = new AtomicReference<>();
    private static final AtomicReference<DiskArtifactStore> DISK = new AtomicReference<>();
    private static final InvalidationStats INVALIDATIONS = new InvalidationStats();
    private static final ChunkPipelineStats PIPELINE = new ChunkPipelineStats();
    private static final DoppelgangerStats DOPPELGANGERS = new DoppelgangerStats();
    private static final ServerChunkStats SERVER_STATS = new ServerChunkStats();
    private static final MeshStats STATS = new MeshStats();
    private static final PrototypeMetrics METRICS = new PrototypeMetrics();
    private static final ArtifactStore ARTIFACTS = new ArtifactStore(RAM_CACHE_BYTES);
    private static Path diskRoot;
    private static String resourceChecksum;

    private InsulinCommon() {
    }

    public static void init(BackendIdentity backend, BackendMeshRestorer restorer, Path gameDirectory) {
        Objects.requireNonNull(backend, "backend");
        Objects.requireNonNull(restorer, "restorer");
        Objects.requireNonNull(gameDirectory, "gameDirectory");

        BackendIdentity previous = BACKEND.compareAndExchange(null, backend);
        if (previous != null && !previous.equals(backend)) {
            throw new IllegalStateException("Insulin was initialized twice with different renderer backends");
        }

        if (previous == null) {
            RESTORER.set(restorer);
            diskRoot = gameDirectory.resolve("insulin-cache").resolve(
                backend.id() + '-' + backend.rendererVersion().replaceAll("[^a-zA-Z0-9._-]", "_")
                    + "-codec" + backend.codecVersion()
            );
            diskRoot = diskRoot.resolve("region-v2-lz4");
            LOGGER.info("Insulin {} capture prototype initialized with {} {} (codec {})",
                TARGET_VERSION, backend.id(), backend.rendererVersion(), backend.codecVersion());
        }
    }

    public static BackendIdentity backend() {
        BackendIdentity backend = BACKEND.get();
        if (backend == null) {
            throw new IllegalStateException("Insulin has not been initialized");
        }
        return backend;
    }

    public static PrototypeMetrics metrics() {
        return METRICS;
    }

    public static MeshStats stats() {
        return STATS;
    }

    public static InvalidationStats invalidations() {
        return INVALIDATIONS;
    }

    public static ChunkPipelineStats pipeline() {
        return PIPELINE;
    }

    public static DoppelgangerStats doppelgangers() {
        return DOPPELGANGERS;
    }

    public static ServerChunkStats serverStats() {
        return SERVER_STATS;
    }

    public static BackendMeshRestorer restorer() {
        BackendMeshRestorer restorer = RESTORER.get();
        if (restorer == null) {
            throw new IllegalStateException("Insulin has not been initialized");
        }
        return restorer;
    }

    public static ArtifactStore artifacts() {
        return ARTIFACTS;
    }

    public static DiskArtifactStore disk() {
        return DISK.get();
    }

    public static synchronized void switchNamespace(String namespace) {
        DOPPELGANGERS.reset();
        ArtifactStore.Snapshot before = ARTIFACTS.snapshot();
        ARTIFACTS.clear();
        INVALIDATIONS.record("namespace_change", before.sections(), before.currentBytes());
        DiskArtifactStore previous = DISK.getAndSet(null);
        if (previous != null) {
            previous.close();
        }
        DISK.set(namespace == null ? null : new DiskArtifactStore(diskRoot.resolve(namespace)));
        LOGGER.info("Insulin cache namespace changed: active={}, discardedSections={}, discardedBytes={}, generation={}",
            namespace != null, before.sections(), before.currentBytes(), ARTIFACTS.snapshot().generation());
    }

    public static void invalidate(String reason) {
        ArtifactStore.Snapshot before = ARTIFACTS.snapshot();
        ARTIFACTS.clear();
        INVALIDATIONS.record(reason, before.sections(), before.currentBytes());
        LOGGER.info("Insulin cache invalidated: reason={}, discardedSections={}, discardedBytes={}, generation={}",
            reason, before.sections(), before.currentBytes(), ARTIFACTS.snapshot().generation());
    }

    public static synchronized void invalidateResources() {
        ArtifactStore.Snapshot before = ARTIFACTS.snapshot();
        ARTIFACTS.clear();
        INVALIDATIONS.record("client_resources", before.sections(), before.currentBytes());
        DiskArtifactStore disk = DISK.get();
        if (disk != null) {
            disk.clear();
        }
        if (diskRoot != null && Files.exists(diskRoot)) {
            try (var paths = Files.walk(diskRoot)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException exception) {
                LOGGER.warn("Insulin storage wipe failed for {}", diskRoot, exception);
            }
        }
        LOGGER.info("Insulin cache invalidated: reason=client_resources, discardedSections={}, "
            + "discardedBytes={}, generation={}", before.sections(), before.currentBytes(),
            ARTIFACTS.snapshot().generation());
    }

    public static synchronized void updateResourceChecksum(String checksum) {
        if (Objects.equals(resourceChecksum, checksum)) {
            return;
        }
        Path checksumFile = diskRoot.resolve("resources.sha256");
        String previous = null;
        try {
            if (Files.exists(checksumFile)) {
                previous = Files.readString(checksumFile);
            }
        } catch (IOException exception) {
            LOGGER.warn("Insulin resource checksum read failed for {}", checksumFile, exception);
        }
        resourceChecksum = checksum;
        if (previous != null && !checksum.equals(previous)) {
            invalidateResources();
        }
        try {
            Files.createDirectories(diskRoot);
            Files.writeString(checksumFile, checksum);
        } catch (IOException exception) {
            LOGGER.warn("Insulin resource checksum write failed for {}", checksumFile, exception);
        }
    }

    public static synchronized void wipeStorage() {
        ArtifactStore.Snapshot before = ARTIFACTS.snapshot();
        ARTIFACTS.clear();
        INVALIDATIONS.record("storage_wipe", before.sections(), before.currentBytes());
        DiskArtifactStore disk = DISK.get();
        if (disk != null) {
            disk.clear();
        }
        LOGGER.info("Insulin storage wiped: discardedSections={}, discardedBytes={}",
            before.sections(), before.currentBytes());
    }

    public static synchronized void close() {
        DiskArtifactStore disk = DISK.getAndSet(null);
        if (disk != null) {
            disk.close();
        }
    }
}
