package net.fly.insulin.cache;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import net.fly.insulin.InsulinCommon;
import net.fly.insulin.backend.PreparedSectionArtifact;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.GraphDirectionSet;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.VisibilityEncoding;
import me.jellysquid.mods.sodium.client.render.viewport.Viewport;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4FastDecompressor;

public final class DiskArtifactStore {

    private static final int MAGIC = 0x494E5352;
    private static final int FORMAT_VERSION = 4;
    private static final int HEADER_BYTES = 52;
    private static final int MAX_RECORD_BYTES = 32 * 1024 * 1024;
    private static final int FLAG_RAW = 0;
    private static final int FLAG_LZ4 = 1;
    private static final long FULL_VISIBILITY = fullVisibility();
    private static final long MIN_COMPACT_BYTES = 16L * 1024L * 1024L;
    private static final LZ4Compressor COMPRESSOR = LZ4Factory.fastestInstance().fastCompressor();
    private static final LZ4FastDecompressor DECOMPRESSOR = LZ4Factory.fastestInstance().fastDecompressor();

    private final Path directory;
    private final Map<RegionKey, RegionFile> regions = new ConcurrentHashMap<>();
    private final ConcurrentMap<SectionArtifactKey, SectionArtifact> pending = new ConcurrentHashMap<>();
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicLong lookupMissing = new AtomicLong();
    private final AtomicLong lookupMismatches = new AtomicLong();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Insulin Region Writer");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService reader = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Insulin Region Reader");
        thread.setDaemon(true);
        return thread;
    });
    private volatile Snapshot snapshot = Snapshot.empty();
    private volatile long snapshotNanos;

    public DiskArtifactStore(Path directory) {
        this.directory = directory;
    }

    public SectionArtifact get(SectionArtifactKey key, SectionFingerprint fingerprint) {
        long started = System.nanoTime();
        try {
            Lookup lookup = this.region(key).lookup(key, fingerprint);
            SectionArtifact artifact = lookup.artifact();
            if (lookup.outcome() == LookupOutcome.MISSING) {
                this.lookupMissing.incrementAndGet();
            } else if (lookup.outcome() == LookupOutcome.MISMATCH) {
                this.lookupMismatches.incrementAndGet();
            }
            InsulinCommon.pipeline().recordCacheDiskRead(key, System.nanoTime() - started, artifact != null);
            return artifact;
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
            InsulinCommon.pipeline().recordCacheDiskRead(key, System.nanoTime() - started, false);
            return null;
        } catch (IOException | RuntimeException exception) {
            InsulinCommon.pipeline().recordCacheDiskRead(key, System.nanoTime() - started, false);
            InsulinCommon.LOGGER.warn("Insulin disk cache read failed for section {},{},{}",
                key.sectionX(), key.sectionY(), key.sectionZ(), exception);
            return null;
        }
    }

    public LookupSnapshot lookupSnapshot() {
        return new LookupSnapshot(this.lookupMissing.get(), this.lookupMismatches.get());
    }

    public void resetLookupStats() {
        this.lookupMissing.set(0L);
        this.lookupMismatches.set(0L);
    }

    public SectionArtifact getLatest(SectionArtifactKey key, int[] rendererStates) {
        try {
            SectionArtifact artifact = this.region(key).getLatest(key);
            if (artifact == null) {
                return null;
            }
            for (int rendererState : rendererStates) {
                if (artifact.rendererState() == rendererState) {
                    return artifact;
                }
            }
            return null;
        } catch (ClosedByInterruptException exception) {
            Thread.currentThread().interrupt();
            return null;
        } catch (IOException | RuntimeException exception) {
            InsulinCommon.LOGGER.warn("Insulin speculative disk cache read failed for section {},{},{}",
                key.sectionX(), key.sectionY(), key.sectionZ(), exception);
            return null;
        }
    }

    public Future<?> prefetchNearby(
        int centerX,
        int centerY,
        int centerZ,
        double cameraX,
        double cameraY,
        double cameraZ,
        float viewX,
        float viewY,
        float viewZ,
        int radius,
        int minimumY,
        int maximumY,
        boolean useOcclusionCulling,
        Viewport viewport,
        int[] rendererStates,
        BlockingQueue<PrefetchedArtifact> destination
    ) {
        return this.reader.submit(() -> this.readNearby(
            centerX,
            centerY,
            centerZ,
            cameraX,
            cameraY,
            cameraZ,
            viewX,
            viewY,
            viewZ,
            radius,
            minimumY,
            maximumY,
            useOcclusionCulling,
            viewport,
            rendererStates,
            destination
        ));
    }

    public void put(SectionArtifactKey key, SectionArtifact artifact) {
        this.pending.put(key, artifact);
        this.scheduleDrain();
    }

    private void scheduleDrain() {
        if (!this.drainScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            this.writer.execute(this::drainPending);
        } catch (RejectedExecutionException ignored) {
            this.drainScheduled.set(false);
        }
    }

    private void drainPending() {
        while (true) {
            Map<RegionKey, List<PendingArtifact>> batches = new HashMap<>();
            int batchSize = 0;
            for (var entry : this.pending.entrySet()) {
                if (batchSize == 128) {
                    break;
                }
                if (this.pending.remove(entry.getKey(), entry.getValue())) {
                    batches.computeIfAbsent(regionKey(entry.getKey()), ignored -> new ArrayList<>()).add(
                        new PendingArtifact(entry.getKey(), entry.getValue())
                    );
                    batchSize++;
                }
            }
            if (batches.isEmpty()) {
                break;
            }
            for (var entry : batches.entrySet()) {
                try {
                    WriteResult result = this.regions.computeIfAbsent(entry.getKey(), current -> new RegionFile(
                        this.directory.resolve("r." + current.x() + "." + current.z() + ".insulin")
                    )).putAll(entry.getValue());
                    InsulinCommon.metrics().recordDiskWrite(result.storedBytes(), result.rawBytes());
                } catch (IOException | RuntimeException exception) {
                    InsulinCommon.LOGGER.warn("Insulin disk cache batch write failed for region {},{}",
                        entry.getKey().x(), entry.getKey().z(), exception);
                }
            }
        }
        this.drainScheduled.set(false);
        if (!this.pending.isEmpty()) {
            this.scheduleDrain();
        }
    }

    public void clear() {
        this.flush();
        this.closeRegions();
        this.regions.clear();
        this.pending.clear();
        this.snapshot = Snapshot.empty();
        if (!Files.exists(this.directory)) {
            return;
        }
        try (var paths = Files.walk(this.directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            InsulinCommon.LOGGER.warn("Insulin disk cache cleanup failed for {}", this.directory, exception);
        }
    }

    public void close() {
        this.reader.shutdownNow();
        this.writer.shutdown();
        boolean writerStopped = false;
        try {
            writerStopped = this.writer.awaitTermination(2, TimeUnit.SECONDS);
            if (!writerStopped) {
                this.writer.shutdownNow();
                writerStopped = this.writer.awaitTermination(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException exception) {
            this.writer.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            this.reader.awaitTermination(1, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            this.reader.shutdownNow();
            Thread.currentThread().interrupt();
        }
        if (writerStopped) {
            this.closeRegions();
        }
    }

    public Snapshot snapshot() {
        long now = System.nanoTime();
        Snapshot current = this.snapshot;
        if (now - this.snapshotNanos < 1_000_000_000L) {
            return current;
        }
        synchronized (this) {
            if (now - this.snapshotNanos < 1_000_000_000L) {
                return this.snapshot;
            }
            long sections = 0L;
            long bytes = 0L;
            if (Files.isDirectory(this.directory)) {
                try (var paths = Files.list(this.directory)) {
                    for (Path path : paths.filter(DiskArtifactStore::isRegionFile).toList()) {
                        RegionFile region = this.regions.computeIfAbsent(regionKey(path), ignored -> new RegionFile(path));
                        RegionSnapshot regionSnapshot = region.snapshot();
                        sections += regionSnapshot.sections();
                        bytes += regionSnapshot.bytes();
                    }
                } catch (ClosedByInterruptException exception) {
                    Thread.currentThread().interrupt();
                    return this.snapshot;
                } catch (IOException exception) {
                    InsulinCommon.LOGGER.warn("Insulin disk cache statistics failed for {}", this.directory, exception);
                }
            }
            this.snapshot = new Snapshot(sections, bytes);
            this.snapshotNanos = now;
            return this.snapshot;
        }
    }

    private void flush() {
        try {
            this.writer.submit(() -> {
            }).get();
        } catch (Exception exception) {
            InsulinCommon.LOGGER.warn("Insulin disk cache flush failed for {}", this.directory, exception);
        }
    }

    private RegionFile region(SectionArtifactKey key) {
        RegionKey regionKey = regionKey(key);
        return this.regions.computeIfAbsent(regionKey, current -> new RegionFile(this.directory.resolve(
            "r." + current.x() + "." + current.z() + ".insulin")));
    }

    private void readNearby(
        int centerX,
        int centerY,
        int centerZ,
        double cameraX,
        double cameraY,
        double cameraZ,
        float viewX,
        float viewY,
        float viewZ,
        int radius,
        int minimumY,
        int maximumY,
        boolean useOcclusionCulling,
        Viewport viewport,
        int[] rendererStates,
        BlockingQueue<PrefetchedArtifact> destination
    ) {
        int minimumX = centerX - radius;
        int maximumX = centerX + radius;
        int minimumZ = centerZ - radius;
        int maximumZ = centerZ + radius;
        List<SectionArtifactKey> keys = new ArrayList<>();
        for (int regionX = Math.floorDiv(minimumX, 32); regionX <= Math.floorDiv(maximumX, 32); regionX++) {
            for (int regionZ = Math.floorDiv(minimumZ, 32); regionZ <= Math.floorDiv(maximumZ, 32); regionZ++) {
                RegionKey regionKey = new RegionKey(regionX, regionZ);
                RegionFile region = this.regions.computeIfAbsent(regionKey, current -> new RegionFile(this.directory.resolve(
                    "r." + current.x() + "." + current.z() + ".insulin"
                )));
                try {
                    region.collectKeys(minimumX, maximumX, minimumY, maximumY, minimumZ, maximumZ, keys);
                } catch (ClosedByInterruptException exception) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException exception) {
                    InsulinCommon.LOGGER.warn("Insulin speculative cache index failed for region {},{}", regionX, regionZ,
                        exception);
                }
            }
        }
        Set<SectionArtifactKey> available = new HashSet<>(keys);
        Set<SectionArtifactKey> discovered = new HashSet<>();
        Map<SectionArtifactKey, Integer> incomingDirections = new HashMap<>();
        int originY = Math.max(minimumY, Math.min(maximumY, centerY));
        SectionArtifactKey origin = new SectionArtifactKey(centerX, originY, centerZ);
        List<SectionArtifactKey> current = new ArrayList<>();
        current.add(origin);
        discovered.add(origin);

        Comparator<SectionArtifactKey> priority = Comparator
            .<SectionArtifactKey>comparingInt(key -> viewTier(
                key, cameraX, cameraY, cameraZ, viewX, viewY, viewZ))
            .thenComparingDouble(key -> distanceSquared(key, cameraX, cameraY, cameraZ))
            .thenComparingLong(key -> horizontalDistanceSquared(key, centerX, centerZ))
            .thenComparingInt(key -> Math.abs(key.sectionY() - centerY));

        boolean firstLayer = true;
        while (!current.isEmpty() && !Thread.currentThread().isInterrupted()) {
            current.sort(priority);
            List<SectionArtifactKey> next = new ArrayList<>();
            for (SectionArtifactKey key : current) {
                if (!isWithinSearchDistance(key, cameraX, cameraY, cameraZ, radius)
                    || !isWithinFrustum(key, viewport)) {
                    continue;
                }

                SectionArtifact artifact = available.contains(key) ? this.getLatest(key, rendererStates) : null;
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                if (artifact != null && !enqueuePrepared(key, artifact, destination)) {
                    return;
                }

                int connections;
                if (useOcclusionCulling) {
                    long visibilityData = artifact == null ? FULL_VISIBILITY : artifact.visibilityData();
                    if (!firstLayer) {
                        visibilityData &= angleVisibilityMask(key, cameraX, cameraY, cameraZ);
                    }
                    connections = firstLayer
                        ? VisibilityEncoding.getConnections(visibilityData)
                        : VisibilityEncoding.getConnections(
                            visibilityData,
                            incomingDirections.getOrDefault(key, GraphDirectionSet.NONE)
                        );
                } else {
                    connections = GraphDirectionSet.ALL;
                }
                connections &= outwardDirections(key, centerX, centerY, centerZ);

                for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
                    if (!GraphDirectionSet.contains(connections, direction)) {
                        continue;
                    }
                    SectionArtifactKey neighbor = new SectionArtifactKey(
                        key.sectionX() + GraphDirection.x(direction),
                        key.sectionY() + GraphDirection.y(direction),
                        key.sectionZ() + GraphDirection.z(direction)
                    );
                    if (neighbor.sectionX() < minimumX || neighbor.sectionX() > maximumX
                        || neighbor.sectionY() < minimumY || neighbor.sectionY() > maximumY
                        || neighbor.sectionZ() < minimumZ || neighbor.sectionZ() > maximumZ) {
                        continue;
                    }
                    incomingDirections.merge(
                        neighbor,
                        GraphDirectionSet.of(GraphDirection.opposite(direction)),
                        (left, right) -> left | right
                    );
                    if (discovered.add(neighbor)) {
                        next.add(neighbor);
                    }
                }
            }
            current = next;
            firstLayer = false;
        }
    }

    private static boolean enqueuePrepared(
        SectionArtifactKey key,
        SectionArtifact artifact,
        BlockingQueue<PrefetchedArtifact> destination
    ) {
        PreparedSectionArtifact prepared = InsulinCommon.restorer().prepare(artifact);
        try {
            destination.put(new PrefetchedArtifact(key, artifact, prepared));
            return true;
        } catch (InterruptedException exception) {
            prepared.free();
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean isWithinSearchDistance(
        SectionArtifactKey key,
        double cameraX,
        double cameraY,
        double cameraZ,
        int radius
    ) {
        double x = nearestToZero(key.sectionX() * 16.0D - cameraX, (key.sectionX() + 1) * 16.0D - cameraX);
        double y = nearestToZero(key.sectionY() * 16.0D - cameraY, (key.sectionY() + 1) * 16.0D - cameraY);
        double z = nearestToZero(key.sectionZ() * 16.0D - cameraZ, (key.sectionZ() + 1) * 16.0D - cameraZ);
        double maximumDistance = radius * 16.0D;
        return x * x + z * z < maximumDistance * maximumDistance && Math.abs(y) < maximumDistance;
    }

    private static boolean isWithinFrustum(SectionArtifactKey key, Viewport viewport) {
        return viewport.isBoxVisible(
            (key.sectionX() << 4) + 8,
            (key.sectionY() << 4) + 8,
            (key.sectionZ() << 4) + 8,
            9.125F,
            9.125F,
            9.125F
        );
    }

    private static long angleVisibilityMask(
        SectionArtifactKey key,
        double cameraX,
        double cameraY,
        double cameraZ
    ) {
        double x = Math.abs(cameraX - ((key.sectionX() << 4) + 8));
        double y = Math.abs(cameraY - ((key.sectionY() << 4) + 8));
        double z = Math.abs(cameraZ - ((key.sectionZ() << 4) + 8));
        long mask = 0L;
        if (x > y || z > y) {
            mask |= visibilityPair(GraphDirection.DOWN, GraphDirection.UP);
            mask |= visibilityPair(GraphDirection.UP, GraphDirection.DOWN);
        }
        if (x > z || y > z) {
            mask |= visibilityPair(GraphDirection.NORTH, GraphDirection.SOUTH);
            mask |= visibilityPair(GraphDirection.SOUTH, GraphDirection.NORTH);
        }
        if (y > x || z > x) {
            mask |= visibilityPair(GraphDirection.WEST, GraphDirection.EAST);
            mask |= visibilityPair(GraphDirection.EAST, GraphDirection.WEST);
        }
        return ~mask;
    }

    private static long fullVisibility() {
        long visibility = 0L;
        for (int from = 0; from < GraphDirection.COUNT; from++) {
            for (int to = 0; to < GraphDirection.COUNT; to++) {
                visibility |= visibilityPair(from, to);
            }
        }
        return visibility;
    }

    private static long visibilityPair(int from, int to) {
        return 1L << ((from * 8) + to);
    }

    private static int outwardDirections(
        SectionArtifactKey key,
        int centerX,
        int centerY,
        int centerZ
    ) {
        int directions = GraphDirectionSet.NONE;
        directions |= key.sectionX() <= centerX ? GraphDirectionSet.of(GraphDirection.WEST) : 0;
        directions |= key.sectionX() >= centerX ? GraphDirectionSet.of(GraphDirection.EAST) : 0;
        directions |= key.sectionY() <= centerY ? GraphDirectionSet.of(GraphDirection.DOWN) : 0;
        directions |= key.sectionY() >= centerY ? GraphDirectionSet.of(GraphDirection.UP) : 0;
        directions |= key.sectionZ() <= centerZ ? GraphDirectionSet.of(GraphDirection.NORTH) : 0;
        directions |= key.sectionZ() >= centerZ ? GraphDirectionSet.of(GraphDirection.SOUTH) : 0;
        return directions;
    }

    private static double nearestToZero(double minimum, double maximum) {
        if (minimum > 0.0D) {
            return minimum;
        }
        if (maximum < 0.0D) {
            return maximum;
        }
        return 0.0D;
    }

    private static RegionKey regionKey(SectionArtifactKey key) {
        return new RegionKey(Math.floorDiv(key.sectionX(), 32), Math.floorDiv(key.sectionZ(), 32));
    }

    private static long horizontalDistanceSquared(SectionArtifactKey key, int centerX, int centerZ) {
        long x = key.sectionX() - centerX;
        long z = key.sectionZ() - centerZ;
        return x * x + z * z;
    }

    private static int viewTier(
        SectionArtifactKey key,
        double cameraX,
        double cameraY,
        double cameraZ,
        float viewX,
        float viewY,
        float viewZ
    ) {
        double x = (key.sectionX() + 0.5D) * 16.0D - cameraX;
        double y = (key.sectionY() + 0.5D) * 16.0D - cameraY;
        double z = (key.sectionZ() + 0.5D) * 16.0D - cameraZ;
        double lengthSquared = x * x + y * y + z * z;
        if (lengthSquared < 1.0D) {
            return 0;
        }

        double alignment = (x * viewX + y * viewY + z * viewZ) / Math.sqrt(lengthSquared);
        if (alignment >= 0.25D) {
            return 0;
        }
        return alignment >= 0.0D ? 1 : 2;
    }

    private static double distanceSquared(
        SectionArtifactKey key,
        double cameraX,
        double cameraY,
        double cameraZ
    ) {
        double x = (key.sectionX() + 0.5D) * 16.0D - cameraX;
        double y = (key.sectionY() + 0.5D) * 16.0D - cameraY;
        double z = (key.sectionZ() + 0.5D) * 16.0D - cameraZ;
        return x * x + y * y + z * z;
    }

    private void closeRegions() {
        for (RegionFile region : this.regions.values()) {
            try {
                region.close();
            } catch (IOException exception) {
                InsulinCommon.LOGGER.warn("Insulin disk cache close failed", exception);
            }
        }
    }

    private static boolean isRegionFile(Path path) {
        String name = path.getFileName().toString();
        return name.startsWith("r.") && name.endsWith(".insulin");
    }

    private static RegionKey regionKey(Path path) {
        String name = path.getFileName().toString();
        String[] values = name.substring(2, name.length() - ".insulin".length()).split("\\.", -1);
        if (values.length != 2) {
            throw new IllegalArgumentException("invalid Insulin region file name: " + name);
        }
        return new RegionKey(Integer.parseInt(values[0]), Integer.parseInt(values[1]));
    }

    private record RegionKey(int x, int z) {

    }

    private record WriteResult(long storedBytes, long rawBytes) {

    }

    private record PendingArtifact(SectionArtifactKey key, SectionArtifact artifact) {

    }

    public record Snapshot(long sections, long bytes) {

        private static Snapshot empty() {
            return new Snapshot(0L, 0L);
        }
    }

    public record LookupSnapshot(long missing, long mismatches) {

    }

    public record PrefetchedArtifact(
        SectionArtifactKey key,
        SectionArtifact artifact,
        PreparedSectionArtifact prepared
    ) {

        public void free() {
            this.prepared.free();
        }

    }

    private record RegionSnapshot(long sections, long bytes) {

    }

    private static final class RegionFile {

        private final Path path;
        private final Map<SectionArtifactKey, Entry> index = new HashMap<>();
        private boolean indexed;
        private long fileBytes;
        private long liveBytes;
        private FileChannel appendChannel;
        private FileChannel readChannel;

        private RegionFile(Path path) {
            this.path = path;
        }

        private synchronized Lookup lookup(SectionArtifactKey key, SectionFingerprint fingerprint)
            throws IOException {
            this.ensureIndexed();
            Entry entry = this.index.get(key);
            if (entry == null) {
                return new Lookup(null, LookupOutcome.MISSING);
            }
            if (!entry.fingerprint().equals(fingerprint)) {
                return new Lookup(null, LookupOutcome.MISMATCH);
            }
            return new Lookup(this.read(key, entry), LookupOutcome.HIT);
        }

        private synchronized SectionArtifact getLatest(SectionArtifactKey key) throws IOException {
            this.ensureIndexed();
            Entry entry = this.index.get(key);
            return entry == null ? null : this.read(key, entry);
        }

        private synchronized void collectKeys(
            int minimumX,
            int maximumX,
            int minimumY,
            int maximumY,
            int minimumZ,
            int maximumZ,
            List<SectionArtifactKey> keys
        ) throws IOException {
            this.ensureIndexed();
            for (SectionArtifactKey key : this.index.keySet()) {
                if (key.sectionX() >= minimumX && key.sectionX() <= maximumX
                    && key.sectionY() >= minimumY && key.sectionY() <= maximumY
                    && key.sectionZ() >= minimumZ && key.sectionZ() <= maximumZ) {
                    keys.add(key);
                }
            }
        }

        private SectionArtifact read(SectionArtifactKey key, Entry entry) throws IOException {
            FileChannel channel = this.readChannel();
            ByteBuffer stored = ByteBuffer.allocate(entry.storedLength());
            readFully(channel, stored, entry.offset());
            byte[] storedBytes = stored.array();
            if (checksum(storedBytes) != entry.checksum()) {
                this.index.remove(key);
                return null;
            }
            byte[] rawBytes;
            if (entry.flags() == FLAG_RAW) {
                if (entry.storedLength() != entry.rawLength()) {
                    this.index.remove(key);
                    return null;
                }
                rawBytes = storedBytes;
            } else if (entry.flags() == FLAG_LZ4) {
                rawBytes = new byte[entry.rawLength()];
                DECOMPRESSOR.decompress(storedBytes, 0, rawBytes, 0, rawBytes.length);
            } else {
                this.index.remove(key);
                return null;
            }
            return decode(rawBytes);
        }

        private synchronized WriteResult putAll(List<PendingArtifact> artifacts) throws IOException {
            this.ensureIndexed();
            Files.createDirectories(this.path.getParent());
            FileChannel channel = this.appendChannel();
            long storedBytes = 0L;
            long rawBytes = 0L;
            for (PendingArtifact pending : artifacts) {
                SectionArtifactKey key = pending.key();
                SectionArtifact artifact = pending.artifact();
                byte[] raw = encode(key, artifact);
                byte[] compressed = new byte[COMPRESSOR.maxCompressedLength(raw.length)];
                int compressedLength = COMPRESSOR.compress(raw, 0, raw.length, compressed, 0, compressed.length);
                int flags = compressedLength < raw.length ? FLAG_LZ4 : FLAG_RAW;
                byte[] stored = flags == FLAG_LZ4 ? Arrays.copyOf(compressed, compressedLength) : raw;
                int checksum = checksum(stored);
                long headerOffset = channel.position();
                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                header.putInt(MAGIC);
                header.putInt(FORMAT_VERSION);
                header.putInt(flags);
                header.putInt(stored.length);
                header.putInt(raw.length);
                header.putInt(checksum);
                header.putInt(key.sectionX());
                header.putInt(key.sectionY());
                header.putInt(key.sectionZ());
                header.putLong(artifact.fingerprint().low());
                header.putLong(artifact.fingerprint().high());
                header.flip();
                writeFully(channel, header);
                writeFully(channel, ByteBuffer.wrap(stored));
                Entry previous = this.index.put(key, new Entry(
                    headerOffset + HEADER_BYTES,
                    stored.length,
                    raw.length,
                    flags,
                    checksum,
                    artifact.fingerprint()
                ));
                if (previous != null) {
                    this.liveBytes -= HEADER_BYTES + previous.storedLength();
                }
                this.liveBytes += HEADER_BYTES + stored.length;
                this.fileBytes = headerOffset + HEADER_BYTES + stored.length;
                storedBytes += stored.length + HEADER_BYTES;
                rawBytes += raw.length + HEADER_BYTES;
            }
            this.compactIfNeeded();
            return new WriteResult(storedBytes, rawBytes);
        }

        private FileChannel appendChannel() throws IOException {
            if (this.appendChannel == null || !this.appendChannel.isOpen()) {
                this.appendChannel = FileChannel.open(this.path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                this.appendChannel.position(this.appendChannel.size());
            }
            return this.appendChannel;
        }

        private FileChannel readChannel() throws IOException {
            if (this.readChannel == null || !this.readChannel.isOpen()) {
                this.readChannel = FileChannel.open(this.path, StandardOpenOption.READ);
            }
            return this.readChannel;
        }

        private synchronized void close() throws IOException {
            try {
                this.closeAppendChannel();
            } finally {
                this.closeReadChannel();
            }
        }

        private void closeAppendChannel() throws IOException {
            if (this.appendChannel != null) {
                this.appendChannel.close();
                this.appendChannel = null;
            }
        }

        private void closeReadChannel() throws IOException {
            if (this.readChannel != null) {
                this.readChannel.close();
                this.readChannel = null;
            }
        }

        private synchronized RegionSnapshot snapshot() throws IOException {
            this.ensureIndexed();
            return new RegionSnapshot(this.index.size(), this.fileBytes);
        }

        private void ensureIndexed() throws IOException {
            if (this.indexed) {
                return;
            }
            this.indexed = true;
            if (!Files.isRegularFile(this.path)) {
                return;
            }

            try (FileChannel channel = FileChannel.open(this.path, StandardOpenOption.READ)) {
                long offset = 0;
                long size = channel.size();
                this.fileBytes = size;
                ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                while (offset + HEADER_BYTES <= size) {
                    header.clear();
                    readFully(channel, header, offset);
                    header.flip();
                    if (header.getInt() != MAGIC || header.getInt() != FORMAT_VERSION) {
                        break;
                    }
                    int flags = header.getInt();
                    int storedLength = header.getInt();
                    int rawLength = header.getInt();
                    int checksum = header.getInt();
                    SectionArtifactKey key = new SectionArtifactKey(header.getInt(), header.getInt(), header.getInt());
                    SectionFingerprint fingerprint = new SectionFingerprint(header.getLong(), header.getLong());
                    long bodyOffset = offset + HEADER_BYTES;
                    if ((flags != FLAG_RAW && flags != FLAG_LZ4) || storedLength < 1
                        || storedLength > COMPRESSOR.maxCompressedLength(MAX_RECORD_BYTES)
                        || rawLength < 40 || rawLength > MAX_RECORD_BYTES
                        || bodyOffset + storedLength > size) {
                        break;
                    }
                    Entry previous = this.index.put(
                        key,
                        new Entry(bodyOffset, storedLength, rawLength, flags, checksum, fingerprint)
                    );
                    if (previous != null) {
                        this.liveBytes -= HEADER_BYTES + previous.storedLength();
                    }
                    this.liveBytes += HEADER_BYTES + storedLength;
                    offset = bodyOffset + storedLength;
                }
            } catch (IOException | RuntimeException exception) {
                this.index.clear();
                this.fileBytes = 0L;
                this.liveBytes = 0L;
                this.indexed = false;
                throw exception;
            }
        }

        private void compactIfNeeded() throws IOException {
            if (this.fileBytes < MIN_COMPACT_BYTES || this.liveBytes * 2 >= this.fileBytes) {
                return;
            }

            this.closeAppendChannel();
            this.closeReadChannel();
            Path temporary = this.path.resolveSibling(this.path.getFileName() + ".compacting");
            Map<SectionArtifactKey, Entry> compacted = new HashMap<>();
            long offset = 0;
            try (FileChannel source = FileChannel.open(this.path, StandardOpenOption.READ);
                 FileChannel target = FileChannel.open(temporary, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                for (Map.Entry<SectionArtifactKey, Entry> indexed : this.index.entrySet()) {
                    SectionArtifactKey key = indexed.getKey();
                    Entry entry = indexed.getValue();
                    ByteBuffer stored = ByteBuffer.allocate(entry.storedLength());
                    readFully(source, stored, entry.offset());
                    stored.flip();

                    ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
                    header.putInt(MAGIC);
                    header.putInt(FORMAT_VERSION);
                    header.putInt(entry.flags());
                    header.putInt(entry.storedLength());
                    header.putInt(entry.rawLength());
                    header.putInt(entry.checksum());
                    header.putInt(key.sectionX());
                    header.putInt(key.sectionY());
                    header.putInt(key.sectionZ());
                    header.putLong(entry.fingerprint().low());
                    header.putLong(entry.fingerprint().high());
                    header.flip();
                    writeFully(target, header);
                    writeFully(target, stored);
                    compacted.put(key, new Entry(
                        offset + HEADER_BYTES,
                        entry.storedLength(),
                        entry.rawLength(),
                        entry.flags(),
                        entry.checksum(),
                        entry.fingerprint()
                    ));
                    offset += HEADER_BYTES + entry.storedLength();
                }
                target.force(false);
            }

            try {
                Files.move(temporary, this.path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, this.path, StandardCopyOption.REPLACE_EXISTING);
            }
            this.index.clear();
            this.index.putAll(compacted);
            this.fileBytes = offset;
            this.liveBytes = offset;
        }

        private static byte[] encode(SectionArtifactKey key, SectionArtifact artifact) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream((int) artifact.retainedBytes() + 128);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(key.sectionX());
                output.writeInt(key.sectionY());
                output.writeInt(key.sectionZ());
                output.writeLong(artifact.fingerprint().low());
                output.writeLong(artifact.fingerprint().high());
                output.writeInt(artifact.rendererState());
                output.writeLong(artifact.visibilityData());
                output.writeInt(artifact.passes().size());
                for (Map.Entry<PassId, CapturedPassArtifact> entry : artifact.passes().entrySet()) {
                    CapturedPassArtifact pass = entry.getValue();
                    ByteBuffer vertexData = pass.vertexData();
                    output.writeInt(entry.getKey().ordinal());
                    output.writeInt(vertexData.remaining());
                    ByteBuffer indexData = pass.indexData();
                    output.writeInt(indexData == null ? -1 : indexData.remaining());
                    output.writeInt(pass.rangeCount());
                    for (int index = 0; index < pass.rangeCount(); index++) {
                        output.writeInt(pass.rangeStart(index));
                        output.writeInt(pass.rangeVertexCount(index));
                    }
                    byte[] packed = new byte[vertexData.remaining()];
                    vertexData.get(packed);
                    output.write(packed);
                    if (indexData != null) {
                        byte[] indices = new byte[indexData.remaining()];
                        indexData.get(indices);
                        output.write(indices);
                    }
                    EmbeddiumSortArtifact sortState = pass.sortState();
                    output.writeBoolean(sortState != null);
                    if (sortState != null) {
                        output.writeInt(sortState.level());
                        writeFloats(output, sortState.centers());
                        writeBytes(output, sortState.normalSigns());
                        writeFloats(output, sortState.sharedNormal());
                    }
                }
                output.writeInt(artifact.animatedSprites().size());
                for (AnimatedSpriteArtifact sprite : artifact.animatedSprites()) {
                    output.writeUTF(sprite.atlas());
                    output.writeUTF(sprite.sprite());
                }
            }
            byte[] result = bytes.toByteArray();
            if (result.length > MAX_RECORD_BYTES) {
                throw new IOException("section artifact exceeds disk record limit");
            }
            return result;
        }

        private static SectionArtifact decode(byte[] bytes) throws IOException {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes))) {
                input.readInt();
                input.readInt();
                input.readInt();
                SectionFingerprint fingerprint = new SectionFingerprint(input.readLong(), input.readLong());
                int rendererState = input.readInt();
                long visibility = input.readLong();
                int passCount = input.readInt();
                if (passCount < 0 || passCount > PassId.values().length) {
                    throw new IOException("invalid pass count");
                }

                Map<PassId, CapturedPassArtifact> passes = new EnumMap<>(PassId.class);
                for (int passIndex = 0; passIndex < passCount; passIndex++) {
                    int passId = input.readInt();
                    int vertexLength = input.readInt();
                    int indexLength = input.readInt();
                    int rangeCount = input.readInt();
                    if (passId < 0 || passId >= PassId.values().length || vertexLength < 0
                        || vertexLength > bytes.length || indexLength < -1 || indexLength > bytes.length
                        || rangeCount < 0 || rangeCount > 64) {
                        throw new IOException("invalid pass metadata");
                    }
                    int[] starts = new int[rangeCount];
                    int[] counts = new int[rangeCount];
                    for (int index = 0; index < rangeCount; index++) {
                        starts[index] = input.readInt();
                        counts[index] = input.readInt();
                    }
                    byte[] vertexData = input.readNBytes(vertexLength);
                    if (vertexData.length != vertexLength) {
                        throw new EOFException("truncated vertex data");
                    }
                    byte[] indexData = indexLength < 0 ? null : input.readNBytes(indexLength);
                    if (indexData != null && indexData.length != indexLength) {
                        throw new EOFException("truncated index data");
                    }
                    EmbeddiumSortArtifact sortState = null;
                    if (input.readBoolean()) {
                        int level = input.readInt();
                        float[] centers = readFloats(input, bytes.length);
                        byte[] normalSigns = readBytes(input, bytes.length);
                        float[] sharedNormal = readFloats(input, 3);
                        sortState = new EmbeddiumSortArtifact(level, centers, normalSigns, sharedNormal);
                    }
                    PassId id = PassId.values()[passId];
                    if (passes.put(id, CapturedPassArtifact.takeOwnership(
                        vertexData, indexData, starts, counts, sortState)) != null) {
                        throw new IOException("duplicate pass");
                    }
                }
                int spriteCount = input.readInt();
                if (spriteCount < 0 || spriteCount > 4096) {
                    throw new IOException("invalid animated sprite count");
                }
                var animatedSprites = new java.util.ArrayList<AnimatedSpriteArtifact>(spriteCount);
                for (int spriteIndex = 0; spriteIndex < spriteCount; spriteIndex++) {
                    animatedSprites.add(new AnimatedSpriteArtifact(input.readUTF(), input.readUTF()));
                }
                if (input.available() != 0) {
                    throw new IOException("trailing section artifact data");
                }
                return new SectionArtifact(Integer.MIN_VALUE, fingerprint, rendererState, visibility, passes, animatedSprites);
            }
        }

        private static int checksum(byte[] bytes) {
            CRC32 checksum = new CRC32();
            checksum.update(bytes);
            return (int) checksum.getValue();
        }

        private static void writeFloats(DataOutputStream output, float[] values) throws IOException {
            output.writeInt(values == null ? -1 : values.length);
            if (values != null) {
                for (float value : values) {
                    output.writeFloat(value);
                }
            }
        }

        private static void writeBytes(DataOutputStream output, byte[] values) throws IOException {
            output.writeInt(values == null ? -1 : values.length);
            if (values != null) {
                output.write(values);
            }
        }

        private static float[] readFloats(DataInputStream input, int maximumLength) throws IOException {
            int length = input.readInt();
            if (length < -1 || length > maximumLength / Float.BYTES) {
                throw new IOException("invalid float array length");
            }
            if (length < 0) {
                return null;
            }
            float[] values = new float[length];
            for (int index = 0; index < values.length; index++) {
                values[index] = input.readFloat();
            }
            return values;
        }

        private static byte[] readBytes(DataInputStream input, int maximumLength) throws IOException {
            int length = input.readInt();
            if (length < -1 || length > maximumLength) {
                throw new IOException("invalid byte array length");
            }
            if (length < 0) {
                return null;
            }
            byte[] values = input.readNBytes(length);
            if (values.length != length) {
                throw new EOFException("truncated byte array");
            }
            return values;
        }

        private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
            while (target.hasRemaining()) {
                int read = channel.read(target, offset + target.position());
                if (read < 0) {
                    throw new EOFException();
                }
            }
        }

        private static void writeFully(FileChannel channel, ByteBuffer source) throws IOException {
            while (source.hasRemaining()) {
                channel.write(source);
            }
        }

        private record Entry(
            long offset,
            int storedLength,
            int rawLength,
            int flags,
            int checksum,
            SectionFingerprint fingerprint
        ) {

        }
    }

    private record Lookup(SectionArtifact artifact, LookupOutcome outcome) {

    }

    private enum LookupOutcome {
        HIT,
        MISSING,
        MISMATCH
    }
}
