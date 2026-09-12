package net.fly.insulin.cache;

import java.nio.ByteBuffer;
import java.util.Objects;

public final class CapturedPassArtifact {

    private final byte[] vertexData;
    private final byte[] indexData;
    private final int[] rangeStarts;
    private final int[] rangeCounts;
    private final EmbeddiumSortArtifact sortState;

    private CapturedPassArtifact(
        byte[] vertexData,
        byte[] indexData,
        int[] rangeStarts,
        int[] rangeCounts,
        EmbeddiumSortArtifact sortState
    ) {
        this.vertexData = vertexData;
        this.indexData = indexData;
        this.rangeStarts = rangeStarts;
        this.rangeCounts = rangeCounts;
        this.sortState = sortState;
    }

    public static CapturedPassArtifact takeOwnership(byte[] vertexData, int[] rangeStarts, int[] rangeCounts) {
        return takeOwnership(vertexData, null, rangeStarts, rangeCounts, null);
    }

    public static CapturedPassArtifact takeOwnership(
        byte[] vertexData,
        byte[] indexData,
        int[] rangeStarts,
        int[] rangeCounts,
        EmbeddiumSortArtifact sortState
    ) {
        Objects.requireNonNull(vertexData, "vertexData");
        Objects.requireNonNull(rangeStarts, "rangeStarts");
        Objects.requireNonNull(rangeCounts, "rangeCounts");
        if (rangeStarts.length != rangeCounts.length) {
            throw new IllegalArgumentException("range arrays must have equal lengths");
        }
        return new CapturedPassArtifact(vertexData, indexData, rangeStarts, rangeCounts, sortState);
    }

    public ByteBuffer vertexData() {
        return ByteBuffer.wrap(this.vertexData).asReadOnlyBuffer();
    }

    public ByteBuffer indexData() {
        return this.indexData == null ? null : ByteBuffer.wrap(this.indexData).asReadOnlyBuffer();
    }

    public EmbeddiumSortArtifact sortState() {
        return this.sortState;
    }

    public int rangeCount() {
        return this.rangeStarts.length;
    }

    public int rangeStart(int index) {
        return this.rangeStarts[index];
    }

    public int rangeVertexCount(int index) {
        return this.rangeCounts[index];
    }

    public long retainedBytes() {
        return this.vertexData.length
            + (this.indexData == null ? 0L : this.indexData.length)
            + (long) Integer.BYTES * (this.rangeStarts.length + this.rangeCounts.length)
            + (this.sortState == null ? 0L : this.sortState.retainedBytes());
    }
}
