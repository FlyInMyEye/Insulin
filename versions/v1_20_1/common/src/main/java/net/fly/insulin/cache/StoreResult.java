package net.fly.insulin.cache;

public record StoreResult(
    Outcome outcome,
    long currentBytes,
    int currentSections,
    long evictedSections,
    long evictedBytes
) {

    public enum Outcome {
        STORED,
        REPLACED,
        REJECTED_STALE,
        REJECTED_TOO_LARGE
    }
}
