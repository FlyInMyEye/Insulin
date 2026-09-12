package net.fly.insulin.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public final class ArtifactStore {

    private final long maximumBytes;
    private final LinkedHashMap<SectionArtifactKey, SectionArtifact> entries =
        new LinkedHashMap<>(256, 0.75f, true);

    private long currentBytes;
    private long generation;

    public ArtifactStore(long maximumBytes) {
        if (maximumBytes <= 0) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }
        this.maximumBytes = maximumBytes;
    }

    public synchronized StoreResult put(SectionArtifactKey key, SectionArtifact artifact, long expectedGeneration) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(artifact, "artifact");

        if (this.generation != expectedGeneration) {
            return this.result(StoreResult.Outcome.REJECTED_STALE, 0, 0);
        }

        if (artifact.retainedBytes() > this.maximumBytes) {
            return this.result(StoreResult.Outcome.REJECTED_TOO_LARGE, 0, 0);
        }

        SectionArtifact previous = this.entries.get(key);
        if (previous != null && previous.buildTime() > artifact.buildTime()) {
            return this.result(StoreResult.Outcome.REJECTED_STALE, 0, 0);
        }

        StoreResult.Outcome outcome = previous == null
            ? StoreResult.Outcome.STORED
            : StoreResult.Outcome.REPLACED;
        if (previous != null) {
            this.entries.remove(key);
            this.currentBytes -= previous.retainedBytes();
        }

        this.entries.put(key, artifact);
        this.currentBytes += artifact.retainedBytes();

        long evictedSections = 0;
        long evictedBytes = 0;
        Iterator<Map.Entry<SectionArtifactKey, SectionArtifact>> iterator = this.entries.entrySet().iterator();
        while (this.currentBytes > this.maximumBytes && iterator.hasNext()) {
            SectionArtifact evicted = iterator.next().getValue();
            iterator.remove();
            this.currentBytes -= evicted.retainedBytes();
            evictedSections++;
            evictedBytes += evicted.retainedBytes();
        }

        return this.result(outcome, evictedSections, evictedBytes);
    }

    public synchronized SectionArtifact get(
        SectionArtifactKey key,
        SectionFingerprint fingerprint,
        long expectedGeneration
    ) {
        if (this.generation != expectedGeneration) {
            return null;
        }
        SectionArtifact artifact = this.entries.get(Objects.requireNonNull(key, "key"));
        if (artifact == null || !artifact.fingerprint().equals(Objects.requireNonNull(fingerprint, "fingerprint"))) {
            return null;
        }
        return artifact;
    }

    public synchronized SectionArtifact getLatest(SectionArtifactKey key, long expectedGeneration) {
        if (this.generation != expectedGeneration) {
            return null;
        }
        return this.entries.get(Objects.requireNonNull(key, "key"));
    }

    public synchronized Snapshot snapshot() {
        return new Snapshot(this.entries.size(), this.currentBytes, this.maximumBytes, this.generation);
    }

    public synchronized long generation() {
        return this.generation;
    }

    public synchronized void clear() {
        this.entries.clear();
        this.currentBytes = 0;
        this.generation++;
    }

    private StoreResult result(StoreResult.Outcome outcome, long evictedSections, long evictedBytes) {
        return new StoreResult(outcome, this.currentBytes, this.entries.size(), evictedSections, evictedBytes);
    }

    public record Snapshot(int sections, long currentBytes, long maximumBytes, long generation) {

    }
}
