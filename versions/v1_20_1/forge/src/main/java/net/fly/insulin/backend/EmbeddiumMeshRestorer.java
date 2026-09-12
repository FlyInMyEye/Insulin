package net.fly.insulin.backend;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.Map;
import me.jellysquid.mods.sodium.client.SodiumClientMod;
import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.render.chunk.RenderSection;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import me.jellysquid.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import me.jellysquid.mods.sodium.client.render.chunk.occlusion.GraphDirection;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.fly.insulin.cache.AnimatedSpriteArtifact;
import net.fly.insulin.cache.CapturedPassArtifact;
import net.fly.insulin.cache.EmbeddiumSortArtifact;
import net.fly.insulin.cache.PassId;
import net.fly.insulin.cache.SectionArtifact;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.resources.ResourceLocation;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer.Level;
import org.embeddedt.embeddium.render.chunk.sorting.TranslucentQuadAnalyzer.SortState;
import org.joml.Vector3f;

public final class EmbeddiumMeshRestorer implements BackendMeshRestorer {

    @Override
    public int stateFingerprint() {
        Minecraft client = Minecraft.getInstance();
        int result = client.options.graphicsMode().get().ordinal();
        result = 31 * result + Boolean.hashCode(client.options.ambientOcclusion().get());
        result = 31 * result + client.options.biomeBlendRadius().get();
        result = 31 * result + client.options.resourcePacks.hashCode();
        result = 31 * result + client.options.incompatibleResourcePacks.hashCode();
        result = 31 * result + SodiumClientMod.options().quality.leavesQuality.ordinal();
        result = 31 * result + Boolean.hashCode(SodiumClientMod.options().quality.useQuadNormalsForShading);
        result = 31 * result + Boolean.hashCode(SodiumClientMod.canUseVanillaVertices());
        return result;
    }

    @Override
    public boolean supportsTranslucentCaching() {
        return true;
    }

    @Override
    public CapturedPassArtifact capture(BuiltSectionMeshParts mesh) {
        VertexRange[] ranges = mesh.getVertexRanges();
        int[] rangeStarts = new int[ranges.length];
        int[] rangeCounts = new int[ranges.length];
        for (int index = 0; index < ranges.length; index++) {
            VertexRange range = ranges[index];
            if (range != null) {
                rangeStarts[index] = range.vertexStart();
                rangeCounts[index] = range.vertexCount();
            }
        }
        return CapturedPassArtifact.takeOwnership(
            copy(mesh.getVertexData()),
            mesh.getIndexData() == null ? null : copy(mesh.getIndexData()),
            rangeStarts,
            rangeCounts,
            captureSortState(mesh.getSortState())
        );
    }

    @Override
    public boolean matches(CapturedPassArtifact artifact, BuiltSectionMeshParts mesh) {
        CapturedPassArtifact current = this.capture(mesh);
        return artifact.vertexData().equals(current.vertexData())
            && equal(artifact.indexData(), current.indexData())
            && rangesEqual(artifact, current)
            && sortStatesEqual(artifact.sortState(), current.sortState());
    }

    @Override
    public ChunkBuildOutput restore(RenderSection render, int buildTime, SectionArtifact artifact) {
        PreparedSectionArtifact prepared = this.prepare(artifact);
        return this.restorePrepared(render, buildTime, artifact, prepared);
    }

    @Override
    public PreparedSectionArtifact prepare(SectionArtifact artifact) {
        Map<PassId, BuiltSectionMeshParts> meshes = new EnumMap<>(PassId.class);
        for (Map.Entry<PassId, CapturedPassArtifact> entry : artifact.passes().entrySet()) {
            CapturedPassArtifact captured = entry.getValue();
            meshes.put(entry.getKey(), new BuiltSectionMeshParts(
                buffer(captured.vertexData()),
                buffer(captured.indexData()),
                restoreSortState(captured.sortState()),
                ranges(captured)
            ));
        }
        return new Prepared(meshes);
    }

    @Override
    public ChunkBuildOutput restorePrepared(
        RenderSection render,
        int buildTime,
        SectionArtifact artifact,
        PreparedSectionArtifact prepared
    ) {
        Prepared embeddiumPrepared = (Prepared) prepared;
        BuiltSectionInfo.Builder info = new BuiltSectionInfo.Builder();
        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new IdentityHashMap<>();

        for (Map.Entry<PassId, BuiltSectionMeshParts> entry : embeddiumPrepared.meshes.entrySet()) {
            TerrainRenderPass pass = switch (entry.getKey()) {
                case SOLID -> DefaultTerrainRenderPasses.SOLID;
                case CUTOUT -> DefaultTerrainRenderPasses.CUTOUT;
                case TRANSLUCENT -> DefaultTerrainRenderPasses.TRANSLUCENT;
            };
            meshes.put(pass, entry.getValue());
            info.addRenderPass(pass);
        }
        for (AnimatedSpriteArtifact sprite : artifact.animatedSprites()) {
            info.addSprite(Minecraft.getInstance().getTextureAtlas(new ResourceLocation(sprite.atlas())).apply(
                new ResourceLocation(sprite.sprite())));
        }

        info.setOcclusionData(visibility(artifact.visibilityData()));
        return new ChunkBuildOutput(render, info.build(), meshes, buildTime);
    }

    private static VertexRange[] ranges(CapturedPassArtifact artifact) {
        VertexRange[] ranges = new VertexRange[artifact.rangeCount()];
        for (int index = 0; index < ranges.length; index++) {
            int count = artifact.rangeVertexCount(index);
            if (count != 0) {
                ranges[index] = new VertexRange(artifact.rangeStart(index), count);
            }
        }
        return ranges;
    }

    private static byte[] copy(NativeBuffer buffer) {
        byte[] data = new byte[buffer.getLength()];
        ByteBuffer source = buffer.getDirectBuffer().duplicate();
        source.clear();
        source.limit(data.length);
        source.get(data);
        return data;
    }

    private static NativeBuffer buffer(ByteBuffer data) {
        if (data == null) {
            return null;
        }
        NativeBuffer buffer = new NativeBuffer(data.remaining());
        buffer.getDirectBuffer().put(data);
        return buffer;
    }

    private static EmbeddiumSortArtifact captureSortState(SortState sortState) {
        if (sortState == null) {
            return null;
        }
        SortState compact = sortState.compactForStorage();
        Vector3f sharedNormal = compact.sharedNormal();
        return new EmbeddiumSortArtifact(
            compact.level().ordinal(),
            compact.centers(),
            compact.normalSigns() == null ? null : compact.normalSigns().toByteArray(),
            sharedNormal == null ? null : new float[] {sharedNormal.x, sharedNormal.y, sharedNormal.z}
        );
    }

    private static SortState restoreSortState(EmbeddiumSortArtifact artifact) {
        if (artifact == null) {
            return null;
        }
        Level[] levels = Level.values();
        if (artifact.level() < 0 || artifact.level() >= levels.length) {
            throw new IllegalArgumentException("invalid Embeddium translucent sort level");
        }
        byte[] normalSigns = artifact.normalSigns();
        float[] sharedNormal = artifact.sharedNormal();
        return new SortState(
            levels[artifact.level()],
            artifact.centers(),
            normalSigns == null ? null : BitSet.valueOf(normalSigns),
            sharedNormal == null ? null : new Vector3f(sharedNormal[0], sharedNormal[1], sharedNormal[2])
        );
    }

    private static boolean equal(ByteBuffer first, ByteBuffer second) {
        return first == null ? second == null : first.equals(second);
    }

    private static boolean rangesEqual(CapturedPassArtifact first, CapturedPassArtifact second) {
        if (first.rangeCount() != second.rangeCount()) {
            return false;
        }
        for (int index = 0; index < first.rangeCount(); index++) {
            if (first.rangeStart(index) != second.rangeStart(index)
                || first.rangeVertexCount(index) != second.rangeVertexCount(index)) {
                return false;
            }
        }
        return true;
    }

    private static boolean sortStatesEqual(EmbeddiumSortArtifact first, EmbeddiumSortArtifact second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.level() == second.level()
            && Arrays.equals(first.centers(), second.centers())
            && Arrays.equals(first.normalSigns(), second.normalSigns())
            && Arrays.equals(first.sharedNormal(), second.sharedNormal());
    }

    private static VisibilitySet visibility(long encoded) {
        VisibilitySet visibility = new VisibilitySet();
        for (int from = 0; from < GraphDirection.COUNT; from++) {
            for (int to = 0; to < GraphDirection.COUNT; to++) {
                if ((encoded & 1L << (from * 8 + to)) != 0) {
                    visibility.set(GraphDirection.toEnum(from), GraphDirection.toEnum(to), true);
                }
            }
        }
        return visibility;
    }

    private record Prepared(Map<PassId, BuiltSectionMeshParts> meshes) implements PreparedSectionArtifact {

        @Override
        public void free() {
            for (BuiltSectionMeshParts mesh : this.meshes.values()) {
                mesh.getVertexData().free();
                if (mesh.getIndexData() != null) {
                    mesh.getIndexData().free();
                }
            }
        }
    }
}
