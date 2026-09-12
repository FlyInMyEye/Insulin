package net.fly.insulin.cache;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class SectionArtifact {

    private final int buildTime;
    private final SectionFingerprint fingerprint;
    private final int rendererState;
    private final long visibilityData;
    private final Map<PassId, CapturedPassArtifact> passes;
    private final List<AnimatedSpriteArtifact> animatedSprites;
    private final long retainedBytes;

    public SectionArtifact(
        int buildTime,
        SectionFingerprint fingerprint,
        int rendererState,
        long visibilityData,
        Map<PassId, CapturedPassArtifact> passes,
        List<AnimatedSpriteArtifact> animatedSprites
    ) {
        this.buildTime = buildTime;
        this.fingerprint = Objects.requireNonNull(fingerprint, "fingerprint");
        this.rendererState = rendererState;
        this.visibilityData = visibilityData;
        Objects.requireNonNull(passes, "passes");
        EnumMap<PassId, CapturedPassArtifact> copy = new EnumMap<>(PassId.class);
        copy.putAll(passes);
        if (copy.values().stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("section artifact passes must not contain null values");
        }
        this.passes = Collections.unmodifiableMap(copy);
        this.animatedSprites = List.copyOf(animatedSprites);
        this.retainedBytes = 64L
            + copy.values().stream().mapToLong(CapturedPassArtifact::retainedBytes).sum()
            + this.animatedSprites.stream().mapToLong(sprite ->
                (long) Character.BYTES * (sprite.atlas().length() + sprite.sprite().length())).sum();
    }

    public int buildTime() {
        return this.buildTime;
    }

    public SectionFingerprint fingerprint() {
        return this.fingerprint;
    }

    public int rendererState() {
        return this.rendererState;
    }

    public long visibilityData() {
        return this.visibilityData;
    }

    public Map<PassId, CapturedPassArtifact> passes() {
        return this.passes;
    }

    public List<AnimatedSpriteArtifact> animatedSprites() {
        return this.animatedSprites;
    }

    public long retainedBytes() {
        return this.retainedBytes;
    }
}
