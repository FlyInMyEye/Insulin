package net.fly.insulin.backend;

import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.fly.insulin.cache.CapturedPassArtifact;
import net.fly.insulin.cache.SectionArtifact;

public interface BackendMeshRestorer {

    int stateFingerprint();

    default int[] previewStateFingerprints() {
        return new int[] {this.stateFingerprint()};
    }

    boolean supportsTranslucentCaching();

    CapturedPassArtifact capture(BuiltSectionMeshParts mesh);

    boolean matches(CapturedPassArtifact artifact, BuiltSectionMeshParts mesh);

    default void prepareLiveOutput(ChunkBuildOutput output) {
    }

    default ChunkBuildOutput attachLiveBlockEntities(
        ChunkBuildOutput output,
        ChunkRenderContext renderContext
    ) {
        return output;
    }

    PreparedSectionArtifact prepare(SectionArtifact artifact);

    ChunkBuildOutput restore(RenderSection render, int buildTime, SectionArtifact artifact);

    ChunkBuildOutput restorePrepared(
        RenderSection render,
        int buildTime,
        SectionArtifact artifact,
        PreparedSectionArtifact prepared
    );
}
