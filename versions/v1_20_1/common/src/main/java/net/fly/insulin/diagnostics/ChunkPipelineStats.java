package net.fly.insulin.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.fly.insulin.InsulinCommon;
import net.fly.insulin.cache.SectionArtifactKey;

public final class ChunkPipelineStats {

    private static final long LOG_INTERVAL = 256L;

    private final ConcurrentMap<ColumnKey, Long> packetStarts = new ConcurrentHashMap<>();
    private final ConcurrentMap<ColumnKey, Long> packetApplied = new ConcurrentHashMap<>();
    private final ConcurrentMap<SectionArtifactKey, SectionTiming> sections = new ConcurrentHashMap<>();
    private final LongAdder packetCount = new LongAdder();
    private final LongAdder packetApplyNanos = new LongAdder();
    private final LongAdder packetToSubmitNanos = new LongAdder();
    private final LongAdder submitToMeshNanos = new LongAdder();
    private final LongAdder packetToMeshNanos = new LongAdder();
    private final LongAdder taskNanos = new LongAdder();
    private final LongAdder cacheDiskReadCount = new LongAdder();
    private final LongAdder cacheDiskReadNanos = new LongAdder();
    private final LongAdder cacheDiskHitCount = new LongAdder();
    private final LongAdder readyToUploadNanos = new LongAdder();
    private final LongAdder uploadNanos = new LongAdder();
    private final LongAdder completedSections = new LongAdder();
    private final LongAdder endToEndNanos = new LongAdder();
    private final AtomicLong loggedBucket = new AtomicLong();
    private volatile boolean enabled;

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = false;
        this.packetStarts.clear();
        this.packetApplied.clear();
        this.sections.clear();
        this.packetCount.reset();
        this.packetApplyNanos.reset();
        this.packetToSubmitNanos.reset();
        this.submitToMeshNanos.reset();
        this.packetToMeshNanos.reset();
        this.taskNanos.reset();
        this.cacheDiskReadCount.reset();
        this.cacheDiskReadNanos.reset();
        this.cacheDiskHitCount.reset();
        this.readyToUploadNanos.reset();
        this.uploadNanos.reset();
        this.completedSections.reset();
        this.endToEndNanos.reset();
        this.loggedBucket.set(0L);
        this.enabled = enabled;
    }

    public void beginPacket(int chunkX, int chunkZ) {
        if (this.enabled) {
            this.packetStarts.put(new ColumnKey(chunkX, chunkZ), System.nanoTime());
        }
    }

    public void finishPacket(int chunkX, int chunkZ) {
        if (!this.enabled) {
            return;
        }
        long now = System.nanoTime();
        ColumnKey key = new ColumnKey(chunkX, chunkZ);
        Long started = this.packetStarts.remove(key);
        if (started != null) {
            this.packetCount.increment();
            this.packetApplyNanos.add(now - started);
            this.packetApplied.put(key, now);
        }
    }

    public SectionArtifactKey beginSection(int sectionX, int sectionY, int sectionZ) {
        if (!this.enabled) {
            return null;
        }
        long now = System.nanoTime();
        Long packetCompleted = this.packetApplied.get(new ColumnKey(sectionX, sectionZ));
        if (packetCompleted == null) {
            return null;
        }
        SectionArtifactKey key = new SectionArtifactKey(sectionX, sectionY, sectionZ);
        SectionTiming timing = this.sections.computeIfAbsent(key, ignored -> new SectionTiming(packetCompleted));
        if (timing.startedNanos == 0L) {
            timing.startedNanos = now;
        }
        return key;
    }

    public void submitSection(int sectionX, int sectionY, int sectionZ) {
        if (!this.enabled) {
            return;
        }
        Long packetCompleted = this.packetApplied.get(new ColumnKey(sectionX, sectionZ));
        if (packetCompleted == null) {
            return;
        }
        long now = System.nanoTime();
        SectionTiming timing = this.sections.computeIfAbsent(
            new SectionArtifactKey(sectionX, sectionY, sectionZ),
            ignored -> new SectionTiming(packetCompleted)
        );
        if (timing.submittedNanos == 0L) {
            timing.submittedNanos = now;
        }
    }

    public void finishSection(SectionArtifactKey key) {
        if (!this.enabled || key == null) {
            return;
        }
        SectionTiming timing = this.sections.get(key);
        if (timing != null) {
            timing.builtNanos = System.nanoTime();
        }
    }

    public void recordCacheDiskRead(SectionArtifactKey key, long elapsedNanos, boolean hit) {
        if (!this.enabled || !this.sections.containsKey(key)) {
            return;
        }
        this.cacheDiskReadCount.increment();
        this.cacheDiskReadNanos.add(elapsedNanos);
        if (hit) {
            this.cacheDiskHitCount.increment();
        }
    }

    public long beginUpload() {
        return this.enabled ? System.nanoTime() : 0L;
    }

    public void finishUpload(long startedNanos, Iterable<SectionArtifactKey> keys) {
        if (!this.enabled || startedNanos == 0L) {
            return;
        }
        long finished = System.nanoTime();
        long uploadElapsed = finished - startedNanos;
        int matched = 0;
        for (SectionArtifactKey key : keys) {
            SectionTiming timing = this.sections.get(key);
            if (timing != null && timing.builtNanos != 0L) {
                matched++;
            }
        }
        if (matched == 0) {
            return;
        }
        long perSectionUpload = uploadElapsed / matched;
        for (SectionArtifactKey key : keys) {
            SectionTiming timing = this.sections.remove(key);
            if (timing == null || timing.builtNanos == 0L) {
                continue;
            }
            if (timing.submittedNanos != 0L) {
                this.packetToSubmitNanos.add(timing.submittedNanos - timing.packetAppliedNanos);
                this.submitToMeshNanos.add(timing.startedNanos - timing.submittedNanos);
            }
            this.packetToMeshNanos.add(timing.startedNanos - timing.packetAppliedNanos);
            this.taskNanos.add(timing.builtNanos - timing.startedNanos);
            this.readyToUploadNanos.add(startedNanos - timing.builtNanos);
            this.uploadNanos.add(perSectionUpload);
            this.endToEndNanos.add(finished - timing.packetAppliedNanos);
            this.completedSections.increment();
        }
        long completed = this.completedSections.sum();
        long bucket = completed / LOG_INTERVAL;
        if (bucket != 0L && this.loggedBucket.getAndSet(bucket) != bucket) {
            InsulinCommon.LOGGER.info(
                "Insulin chunk pipeline: sections={}, packetApply={} ms, packetToMesh={} ms, "
                    + "packetToSubmit={} ms, submitToMesh={} ms, cacheDisk={} ms ({} hit / {} lookup), "
                    + "task={} ms, readyToUpload={} ms, "
                    + "upload={} ms, packetToUploaded={} ms",
                completed,
                average(this.packetApplyNanos.sum(), this.packetCount.sum()),
                average(this.packetToMeshNanos.sum(), completed),
                average(this.packetToSubmitNanos.sum(), completed),
                average(this.submitToMeshNanos.sum(), completed),
                average(this.cacheDiskReadNanos.sum(), this.cacheDiskReadCount.sum()),
                this.cacheDiskHitCount.sum(), this.cacheDiskReadCount.sum(),
                average(this.taskNanos.sum(), completed),
                average(this.readyToUploadNanos.sum(), completed),
                average(this.uploadNanos.sum(), completed),
                average(this.endToEndNanos.sum(), completed)
            );
        }
    }

    private static double average(long totalNanos, long count) {
        return count == 0L ? 0.0D : totalNanos / (double) count / 1_000_000.0D;
    }

    private record ColumnKey(int x, int z) {

    }

    private static final class SectionTiming {

        private final long packetAppliedNanos;
        private volatile long submittedNanos;
        private volatile long startedNanos;
        private volatile long builtNanos;

        private SectionTiming(long packetAppliedNanos) {
            this.packetAppliedNanos = packetAppliedNanos;
        }
    }
}
