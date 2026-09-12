package net.fly.insulin.diagnostics;

public record CapturedSectionSummary(
    int sectionX,
    int sectionY,
    int sectionZ,
    int buildTime,
    int passCount,
    long vertexCount,
    long vertexBytes
) {

    public CapturedSectionSummary {
        if (passCount < 0 || vertexCount < 0 || vertexBytes < 0) {
            throw new IllegalArgumentException("mesh summary values must not be negative");
        }
    }
}
