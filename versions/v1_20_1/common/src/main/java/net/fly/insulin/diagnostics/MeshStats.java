package net.fly.insulin.diagnostics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public final class MeshStats {

    private final AtomicLong epoch = new AtomicLong();
    private final LongAdder meshedSections = new LongAdder();
    private final LongAdder meshedNanos = new LongAdder();
    private final LongAdder ramRestoreSections = new LongAdder();
    private final LongAdder ramRestoreNanos = new LongAdder();
    private final LongAdder diskRestoreSections = new LongAdder();
    private final LongAdder diskRestoreNanos = new LongAdder();
    private final LongAdder cacheHits = new LongAdder();
    private final LongAdder cacheMisses = new LongAdder();
    private final LongAdder ramHits = new LongAdder();
    private final LongAdder diskHits = new LongAdder();
    private final ConcurrentMap<String, LongAdder> bypasses = new ConcurrentHashMap<>();
    private volatile boolean enabled;

    public boolean isEnabled() {
        return this.enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        this.enabled = false;
        this.epoch.incrementAndGet();
        if (enabled) {
            this.meshedSections.reset();
            this.meshedNanos.reset();
            this.ramRestoreSections.reset();
            this.ramRestoreNanos.reset();
            this.diskRestoreSections.reset();
            this.diskRestoreNanos.reset();
            this.cacheHits.reset();
            this.cacheMisses.reset();
            this.ramHits.reset();
            this.diskHits.reset();
            this.bypasses.clear();
        }
        this.enabled = enabled;
    }

    public Timing begin() {
        return this.enabled ? new Timing(this.epoch.get(), System.nanoTime()) : null;
    }

    public void recordLookup(Timing timing, LookupResult result) {
        if (!this.accepts(timing)) {
            return;
        }
        if (result == LookupResult.MISS) {
            this.cacheMisses.increment();
            return;
        }
        this.cacheHits.increment();
        if (result == LookupResult.RAM_HIT) {
            this.ramHits.increment();
        } else {
            this.diskHits.increment();
        }
    }

    public void finishMeshing(Timing timing) {
        if (!this.accepts(timing)) {
            return;
        }
        this.meshedNanos.add(System.nanoTime() - timing.startedNanos());
        this.meshedSections.increment();
    }

    public void finishSkipped(Timing timing, LookupResult result) {
        if (!this.accepts(timing)) {
            return;
        }
        long elapsedNanos = System.nanoTime() - timing.startedNanos();
        if (result == LookupResult.RAM_HIT) {
            this.ramRestoreNanos.add(elapsedNanos);
            this.ramRestoreSections.increment();
        } else if (result == LookupResult.DISK_HIT) {
            this.diskRestoreNanos.add(elapsedNanos);
            this.diskRestoreSections.increment();
        }
    }

    public void recordBypass(Timing timing, String reason) {
        if (!this.accepts(timing)) {
            return;
        }
        this.bypasses.computeIfAbsent(reason, ignored -> new LongAdder()).increment();
    }

    public Snapshot snapshot() {
        long meshedCount = this.meshedSections.sum();
        long ramRestoreCount = this.ramRestoreSections.sum();
        long diskRestoreCount = this.diskRestoreSections.sum();
        List<BypassSnapshot> bypassSnapshots = new ArrayList<>(this.bypasses.size());
        for (var entry : this.bypasses.entrySet()) {
            bypassSnapshots.add(new BypassSnapshot(entry.getKey(), entry.getValue().sum()));
        }
        bypassSnapshots.sort(Comparator.comparing(BypassSnapshot::reason));
        return new Snapshot(
            average(this.meshedNanos.sum(), meshedCount),
            meshedCount,
            average(this.ramRestoreNanos.sum(), ramRestoreCount),
            ramRestoreCount,
            average(this.diskRestoreNanos.sum(), diskRestoreCount),
            diskRestoreCount,
            this.cacheHits.sum(),
            this.cacheMisses.sum(),
            this.ramHits.sum(),
            this.diskHits.sum(),
            bypassSnapshots
        );
    }

    private boolean accepts(Timing timing) {
        return timing != null && this.enabled && timing.epoch() == this.epoch.get();
    }

    private static long average(long total, long count) {
        return count == 0L ? 0L : total / count;
    }

    public enum LookupResult {
        MISS,
        RAM_HIT,
        DISK_HIT
    }

    public record Timing(long epoch, long startedNanos) {

    }

    public record Snapshot(
        long averageMeshedNanos,
        long meshedSections,
        long averageRamRestoreNanos,
        long ramRestoreSections,
        long averageDiskRestoreNanos,
        long diskRestoreSections,
        long cacheHits,
        long cacheMisses,
        long ramHits,
        long diskHits,
        List<BypassSnapshot> bypasses
    ) {

    }

    public record BypassSnapshot(String reason, long count) {

    }
}
