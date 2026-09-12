package net.fly.insulin.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import net.fly.insulin.InsulinCommon;

public final class ServerChunkStats {

    private static final long LOG_INTERVAL = 256L;

    private final ConcurrentMap<Long, Long> diskReadStarts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> loadStarts = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Long> packetBuildStarts = new ConcurrentHashMap<>();
    private final LongAdder diskReadCount = new LongAdder();
    private final LongAdder diskReadNanos = new LongAdder();
    private final LongAdder loadCount = new LongAdder();
    private final LongAdder loadNanos = new LongAdder();
    private final LongAdder sendCount = new LongAdder();
    private final LongAdder packetBuildNanos = new LongAdder();
    private final LongAdder loadToSendCount = new LongAdder();
    private final LongAdder loadToSendNanos = new LongAdder();
    private final AtomicLong loggedBucket = new AtomicLong();
    private volatile boolean enabled;

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = false;
        this.diskReadStarts.clear();
        this.loadStarts.clear();
        this.packetBuildStarts.clear();
        this.diskReadCount.reset();
        this.diskReadNanos.reset();
        this.loadCount.reset();
        this.loadNanos.reset();
        this.sendCount.reset();
        this.packetBuildNanos.reset();
        this.loadToSendCount.reset();
        this.loadToSendNanos.reset();
        this.loggedBucket.set(0L);
        this.enabled = enabled;
    }

    public void beginDiskRead(long chunkPos) {
        if (this.enabled) {
            this.diskReadStarts.putIfAbsent(chunkPos, System.nanoTime());
        }
    }

    public void finishDiskRead(long chunkPos) {
        if (!this.enabled) {
            return;
        }
        Long started = this.diskReadStarts.remove(chunkPos);
        if (started != null) {
            this.diskReadCount.increment();
            this.diskReadNanos.add(System.nanoTime() - started);
        }
    }

    public void beginLoad(long chunkPos) {
        if (this.enabled) {
            this.loadStarts.putIfAbsent(chunkPos, System.nanoTime());
        }
    }

    public void finishLoad(long chunkPos) {
        if (!this.enabled) {
            return;
        }
        Long started = this.loadStarts.get(chunkPos);
        if (started != null) {
            this.loadCount.increment();
            this.loadNanos.add(System.nanoTime() - started);
        }
    }

    public void beginPacketBuild(long chunkPos) {
        if (this.enabled) {
            this.packetBuildStarts.put(chunkPos, System.nanoTime());
        }
    }

    public void finishSend(long chunkPos) {
        if (!this.enabled) {
            return;
        }
        long now = System.nanoTime();
        this.sendCount.increment();
        Long packetBuildStarted = this.packetBuildStarts.remove(chunkPos);
        if (packetBuildStarted != null) {
            this.packetBuildNanos.add(now - packetBuildStarted);
        }
        Long started = this.loadStarts.remove(chunkPos);
        if (started != null) {
            this.loadToSendCount.increment();
            this.loadToSendNanos.add(now - started);
        }
        long sent = this.sendCount.sum();
        long bucket = sent / LOG_INTERVAL;
        if (bucket != 0L && this.loggedBucket.getAndSet(bucket) != bucket) {
            InsulinCommon.LOGGER.info(
                "Insulin server chunk pipeline: sent={}, regionRead={} ms ({}), loadReady={} ms ({}), "
                    + "packetBuild={} ms, loadToSend={} ms ({})",
                sent,
                average(this.diskReadNanos.sum(), this.diskReadCount.sum()), this.diskReadCount.sum(),
                average(this.loadNanos.sum(), this.loadCount.sum()), this.loadCount.sum(),
                average(this.packetBuildNanos.sum(), sent),
                average(this.loadToSendNanos.sum(), this.loadToSendCount.sum()), this.loadToSendCount.sum()
            );
        }
    }

    private static double average(long totalNanos, long count) {
        return count == 0L ? 0.0D : totalNanos / (double) count / 1_000_000.0D;
    }
}
