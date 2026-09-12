package net.fly.insulin.diagnostics;

import java.util.concurrent.atomic.LongAdder;
import net.fly.insulin.InsulinCommon;
import net.fly.insulin.cache.StoreResult;

public final class PrototypeMetrics {

    private static final long LOG_INTERVAL = 256;

    private final LongAdder completedSections = new LongAdder();
    private final LongAdder emptySections = new LongAdder();
    private final LongAdder passes = new LongAdder();
    private final LongAdder vertices = new LongAdder();
    private final LongAdder vertexBytes = new LongAdder();
    private final LongAdder cacheableSections = new LongAdder();
    private final LongAdder copiedBytes = new LongAdder();
    private final LongAdder evictedSections = new LongAdder();
    private final LongAdder evictedBytes = new LongAdder();
    private final LongAdder skippedTranslucentPasses = new LongAdder();
    private final LongAdder candidateHits = new LongAdder();
    private final LongAdder verifiedMatches = new LongAdder();
    private final LongAdder verifiedMismatches = new LongAdder();
    private final LongAdder restoredSections = new LongAdder();
    private final LongAdder restoredBytes = new LongAdder();
    private final LongAdder ramHits = new LongAdder();
    private final LongAdder diskHits = new LongAdder();
    private final LongAdder diskBytesWritten = new LongAdder();
    private final LongAdder diskRawBytesWritten = new LongAdder();

    public void recordCandidate(boolean disk) {
        this.candidateHits.increment();
        (disk ? this.diskHits : this.ramHits).increment();
    }

    public void recordDiskWrite(long storedBytes, long rawBytes) {
        this.diskBytesWritten.add(storedBytes);
        this.diskRawBytesWritten.add(rawBytes);
    }

    public void recordVerification(boolean matches) {
        (matches ? this.verifiedMatches : this.verifiedMismatches).increment();
    }

    public void recordRestoration(long bytes) {
        this.restoredSections.increment();
        this.restoredBytes.add(bytes);
        long count = this.restoredSections.sum();
        if (count % 64 == 0) {
            InsulinCommon.LOGGER.info(
                "Insulin restore: backend={}, restored={}, restoredBytes={}, candidates={}, ramHits={}, "
                    + "diskHits={}, diskBytesWritten={}, diskRawBytesWritten={}, ramSections={}, ramBytes={}",
                InsulinCommon.backend().id(), count, this.restoredBytes.sum(), this.candidateHits.sum(),
                this.ramHits.sum(), this.diskHits.sum(), this.diskBytesWritten.sum(),
                this.diskRawBytesWritten.sum(), InsulinCommon.artifacts().snapshot().sections(),
                InsulinCommon.artifacts().snapshot().currentBytes()
            );
        }
    }

    public void record(
        CapturedSectionSummary summary,
        StoreResult storeResult,
        long bytesCopied,
        int translucentPasses
    ) {
        this.completedSections.increment();
        if (summary.passCount() == 0) {
            this.emptySections.increment();
        }
        this.passes.add(summary.passCount());
        this.vertices.add(summary.vertexCount());
        this.vertexBytes.add(summary.vertexBytes());
        this.skippedTranslucentPasses.add(translucentPasses);
        if (storeResult != null) {
            this.cacheableSections.increment();
            this.copiedBytes.add(bytesCopied);
            this.evictedSections.add(storeResult.evictedSections());
            this.evictedBytes.add(storeResult.evictedBytes());
        }

        long count = this.completedSections.sum();
        if (count % LOG_INTERVAL == 0) {
            InsulinCommon.LOGGER.info(
                "Insulin capture: backend={}, sections={}, empty={}, passes={}, vertices={}, vertexBytes={}, "
                    + "ramSections={}, ramBytes={}, copiedBytes={}, evictedSections={}, "
                    + "evictedBytes={}, skippedTranslucent={}, candidates={}, verified={}, mismatches={}",
                InsulinCommon.backend().id(), count, this.emptySections.sum(), this.passes.sum(),
                this.vertices.sum(), this.vertexBytes.sum(),
                storeResult == null ? InsulinCommon.artifacts().snapshot().sections() : storeResult.currentSections(),
                storeResult == null ? InsulinCommon.artifacts().snapshot().currentBytes() : storeResult.currentBytes(),
                this.copiedBytes.sum(), this.evictedSections.sum(), this.evictedBytes.sum(),
                this.skippedTranslucentPasses.sum(), this.candidateHits.sum(), this.verifiedMatches.sum(),
                this.verifiedMismatches.sum()
            );
        }
    }

    public Snapshot snapshot() {
        return new Snapshot(
            this.completedSections.sum(),
            this.emptySections.sum(),
            this.passes.sum(),
            this.vertices.sum(),
            this.vertexBytes.sum(),
            this.cacheableSections.sum(),
            this.copiedBytes.sum(),
            this.evictedSections.sum(),
            this.evictedBytes.sum(),
            this.skippedTranslucentPasses.sum(),
            this.candidateHits.sum(),
            this.verifiedMatches.sum(),
            this.verifiedMismatches.sum(),
            this.restoredSections.sum(),
            this.restoredBytes.sum(),
            this.ramHits.sum(),
            this.diskHits.sum(),
            this.diskBytesWritten.sum(),
            this.diskRawBytesWritten.sum()
        );
    }

    public record Snapshot(
        long completedSections,
        long emptySections,
        long passes,
        long vertices,
        long vertexBytes,
        long cacheableSections,
        long copiedBytes,
        long evictedSections,
        long evictedBytes,
        long skippedTranslucentPasses,
        long candidateHits,
        long verifiedMatches,
        long verifiedMismatches,
        long restoredSections,
        long restoredBytes,
        long ramHits,
        long diskHits,
        long diskBytesWritten,
        long diskRawBytesWritten
    ) {

    }
}
