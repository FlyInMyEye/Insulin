package net.fly.insulin.cache;

import java.util.Arrays;

public final class EmbeddiumSortArtifact {

    private final int level;
    private final float[] centers;
    private final byte[] normalSigns;
    private final float[] sharedNormal;

    public EmbeddiumSortArtifact(int level, float[] centers, byte[] normalSigns, float[] sharedNormal) {
        this.level = level;
        this.centers = centers == null ? null : Arrays.copyOf(centers, centers.length);
        this.normalSigns = normalSigns == null ? null : Arrays.copyOf(normalSigns, normalSigns.length);
        this.sharedNormal = sharedNormal == null ? null : Arrays.copyOf(sharedNormal, sharedNormal.length);
        if (this.sharedNormal != null && this.sharedNormal.length != 3) {
            throw new IllegalArgumentException("shared normal must have three components");
        }
    }

    public int level() {
        return this.level;
    }

    public float[] centers() {
        return this.centers == null ? null : Arrays.copyOf(this.centers, this.centers.length);
    }

    public byte[] normalSigns() {
        return this.normalSigns == null ? null : Arrays.copyOf(this.normalSigns, this.normalSigns.length);
    }

    public float[] sharedNormal() {
        return this.sharedNormal == null ? null : Arrays.copyOf(this.sharedNormal, this.sharedNormal.length);
    }

    public long retainedBytes() {
        return Integer.BYTES
            + (this.centers == null ? 0L : (long) Float.BYTES * this.centers.length)
            + (this.normalSigns == null ? 0L : this.normalSigns.length)
            + (this.sharedNormal == null ? 0L : (long) Float.BYTES * this.sharedNormal.length);
    }
}
