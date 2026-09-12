package net.fly.insulin.backend;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
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
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.fly.insulin.cache.AnimatedSpriteArtifact;
import net.fly.insulin.cache.CapturedPassArtifact;
import net.fly.insulin.cache.PassId;
import net.fly.insulin.cache.SectionArtifact;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;

public final class SodiumMeshRestorer implements BackendMeshRestorer {

    private static final IrisState IRIS = IrisState.create();

    @Override
    public int stateFingerprint() {
        Minecraft client = Minecraft.getInstance();
        int result = client.options.graphicsMode().get().ordinal();
        result = 31 * result + Boolean.hashCode(client.options.ambientOcclusion().get());
        result = 31 * result + client.options.biomeBlendRadius().get();
        result = 31 * result + SodiumClientMod.options().quality.leavesQuality.ordinal();
        result = 31 * result + IRIS.geometryFingerprint();
        return result;
    }

    public static ChunkVertexType sharedVertexType(ChunkVertexType fallback) {
        return IRIS.sharedVertexType(fallback);
    }

    public static int sharedVertexStride(int fallback) {
        return IRIS.sharedVertexStride(fallback);
    }

    @Override
    public boolean supportsTranslucentCaching() {
        return true;
    }

    @Override
    public CapturedPassArtifact capture(BuiltSectionMeshParts mesh) {
        byte[] vertexData = canonicalVertexData(mesh);
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
        return CapturedPassArtifact.takeOwnership(vertexData, rangeStarts, rangeCounts);
    }

    @Override
    public boolean matches(CapturedPassArtifact artifact, BuiltSectionMeshParts mesh) {
        if (!artifact.vertexData().equals(ByteBuffer.wrap(canonicalVertexData(mesh)))) {
            return false;
        }
        VertexRange[] ranges = mesh.getVertexRanges();
        if (artifact.rangeCount() != ranges.length) {
            return false;
        }
        for (int index = 0; index < ranges.length; index++) {
            VertexRange range = ranges[index];
            int start = range == null ? 0 : range.vertexStart();
            int count = range == null ? 0 : range.vertexCount();
            if (artifact.rangeStart(index) != start || artifact.rangeVertexCount(index) != count) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void prepareLiveOutput(ChunkBuildOutput output) {
        for (BuiltSectionMeshParts mesh : output.meshes.values()) {
            applyActiveLighting(mesh.getVertexData(), !IRIS.shouldDisableDirectionalShading());
            patchShaderBlockIds(mesh.getVertexData());
        }
    }

    @Override
    public ChunkBuildOutput attachLiveBlockEntities(
        ChunkBuildOutput output,
        ChunkRenderContext renderContext
    ) {
        ClonedChunkSection origin = originSection(renderContext);
        if (origin == null || origin.getBlockEntityMap() == null) {
            return output;
        }

        BuiltSectionInfo.Builder info = new BuiltSectionInfo.Builder();
        for (TerrainRenderPass pass : output.meshes.keySet()) {
            info.addRenderPass(pass);
        }
        if (output.info.animatedSprites != null) {
            for (var sprite : output.info.animatedSprites) {
                info.addSprite(sprite);
            }
        }

        var dispatcher = Minecraft.getInstance().getBlockEntityRenderDispatcher();
        for (BlockEntity entity : origin.getBlockEntityMap().values()) {
            BlockEntityRenderer<BlockEntity> renderer = dispatcher.getRenderer(entity);
            if (renderer != null) {
                info.addBlockEntity(entity, !renderer.shouldRenderOffScreen(entity));
            }
        }

        info.setOcclusionData(visibility(output.info.visibilityData));
        return new ChunkBuildOutput(output.render, info.build(), output.meshes, output.buildTime);
    }

    private static ClonedChunkSection originSection(ChunkRenderContext context) {
        for (ClonedChunkSection section : context.getSections()) {
            if (section != null && section.getPosition().equals(context.getOrigin())) {
                return section;
            }
        }
        return null;
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
            NativeBuffer buffer = buffer(captured);
            prepareCachedVertices(buffer);
            meshes.put(entry.getKey(), new BuiltSectionMeshParts(buffer, ranges(captured)));
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
        Prepared sodiumPrepared = (Prepared) prepared;
        BuiltSectionInfo.Builder info = new BuiltSectionInfo.Builder();
        Map<TerrainRenderPass, BuiltSectionMeshParts> meshes = new IdentityHashMap<>();

        for (Map.Entry<PassId, BuiltSectionMeshParts> entry : sodiumPrepared.meshes.entrySet()) {
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

    private static NativeBuffer buffer(CapturedPassArtifact artifact) {
        NativeBuffer buffer = new NativeBuffer(artifact.vertexData().remaining());
        buffer.getDirectBuffer().put(artifact.vertexData());
        return buffer;
    }

    private static byte[] canonicalVertexData(BuiltSectionMeshParts mesh) {
        int length = mesh.getVertexData().getLength();
        byte[] vertexData = new byte[length];
        ByteBuffer source = mesh.getVertexData().getDirectBuffer().duplicate();
        source.clear();
        source.limit(length);
        source.get(vertexData);
        if (IRIS.shouldDisableDirectionalShading()) {
            transformDirectionalShading(vertexData, true);
        }
        return vertexData;
    }

    private static void transformDirectionalShading(byte[] data, boolean addShading) {
        int quadStride = IrisCanonicalVertexData.STRIDE * 4;
        if (data.length % quadStride != 0) {
            return;
        }
        for (int quadOffset = 0; quadOffset < data.length; quadOffset += quadStride) {
            byte metadata = data[quadOffset + IrisCanonicalVertexData.NORMAL_PADDING_OFFSET];
            float multiplier = directionalShade(metadata);
            if (multiplier == 1.0f) {
                continue;
            }
            for (int vertex = 0; vertex < 4; vertex++) {
                int alphaOffset = quadOffset + vertex * IrisCanonicalVertexData.STRIDE
                    + IrisCanonicalVertexData.COLOR_ALPHA_OFFSET;
                int alpha = data[alphaOffset] & 0xff;
                int transformed = addShading
                    ? (int) (alpha * multiplier)
                    : Math.min(255, Math.round(alpha / multiplier));
                data[alphaOffset] = (byte) transformed;
            }
        }
    }

    private static void prepareCachedVertices(NativeBuffer buffer) {
        applyActiveLighting(buffer, true);
        Map<?, ?> blockStateIds = IRIS.blockStateIds();
        int length = buffer.getLength();
        int quadStride = IrisCanonicalVertexData.STRIDE * 4;
        if (blockStateIds == null || length == 0 || length % quadStride != 0) {
            return;
        }

        ByteBuffer data = buffer.getDirectBuffer().duplicate();
        data.clear();
        data.limit(length);
        long base = MemoryUtil.memAddress(data);
        for (int quadOffset = 0; quadOffset < length; quadOffset += quadStride) {
            long firstVertex = base + quadOffset;
            byte metadata = MemoryUtil.memGetByte(
                firstVertex + IrisCanonicalVertexData.NORMAL_PADDING_OFFSET
            );

            int stateId = IrisCanonicalVertexData.stateId(
                metadata,
                MemoryUtil.memGetShort(firstVertex + IrisCanonicalVertexData.BLOCK_ID_OFFSET)
            );

            int shaderBlockId = -1;
            if (stateId != IrisCanonicalVertexData.UNKNOWN_BLOCK_STATE) {
                BlockState state = Block.stateById(stateId);
                Object mapped = state == null ? null : blockStateIds.get(state);
                if (mapped instanceof Integer id) {
                    shaderBlockId = id;
                }
            }

            for (int vertex = 0; vertex < 4; vertex++) {
                MemoryUtil.memPutShort(
                    firstVertex + (long) vertex * IrisCanonicalVertexData.STRIDE
                        + IrisCanonicalVertexData.BLOCK_ID_OFFSET,
                    (short) shaderBlockId
                );
            }
        }
    }

    private static void applyActiveLighting(NativeBuffer buffer, boolean inputHasDirectionalShading) {
        float ambientOcclusionLevel = IRIS.ambientOcclusionLevel();
        boolean outputHasDirectionalShading = !IRIS.shouldDisableDirectionalShading();
        if (ambientOcclusionLevel == 1.0f
            && inputHasDirectionalShading == outputHasDirectionalShading) {
            return;
        }

        int length = buffer.getLength();
        int quadStride = IrisCanonicalVertexData.STRIDE * 4;
        if (length == 0 || length % quadStride != 0) {
            return;
        }

        ByteBuffer data = buffer.getDirectBuffer().duplicate();
        data.clear();
        data.limit(length);
        long base = MemoryUtil.memAddress(data);
        for (int quadOffset = 0; quadOffset < length; quadOffset += quadStride) {
            long firstVertex = base + quadOffset;
            byte metadata = MemoryUtil.memGetByte(
                firstVertex + IrisCanonicalVertexData.NORMAL_PADDING_OFFSET
            );
            float directional = directionalShade(metadata);
            for (int vertex = 0; vertex < 4; vertex++) {
                long alphaAddress = firstVertex + (long) vertex * IrisCanonicalVertexData.STRIDE
                    + IrisCanonicalVertexData.COLOR_ALPHA_OFFSET;
                float brightness = (MemoryUtil.memGetByte(alphaAddress) & 0xff) / 255.0f;
                if (inputHasDirectionalShading && directional > 0.0f) {
                    brightness /= directional;
                }
                brightness = 1.0f - ambientOcclusionLevel * (1.0f - brightness);
                if (outputHasDirectionalShading) {
                    brightness *= directional;
                }
                MemoryUtil.memPutByte(
                    alphaAddress,
                    (byte) Math.min(255, Math.max(0, Math.round(brightness * 255.0f)))
                );
            }
        }
    }

    private static void patchShaderBlockIds(NativeBuffer buffer) {
        Map<?, ?> blockStateIds = IRIS.blockStateIds();
        int length = buffer.getLength();
        int quadStride = IrisCanonicalVertexData.STRIDE * 4;
        if (blockStateIds == null || length == 0 || length % quadStride != 0) {
            return;
        }

        ByteBuffer data = buffer.getDirectBuffer().duplicate();
        data.clear();
        data.limit(length);
        long base = MemoryUtil.memAddress(data);
        for (int quadOffset = 0; quadOffset < length; quadOffset += quadStride) {
            long firstVertex = base + quadOffset;
            byte metadata = MemoryUtil.memGetByte(
                firstVertex + IrisCanonicalVertexData.NORMAL_PADDING_OFFSET
            );
            int stateId = IrisCanonicalVertexData.stateId(
                metadata,
                MemoryUtil.memGetShort(firstVertex + IrisCanonicalVertexData.BLOCK_ID_OFFSET)
            );
            int shaderBlockId = -1;
            if (stateId != IrisCanonicalVertexData.UNKNOWN_BLOCK_STATE) {
                BlockState state = Block.stateById(stateId);
                Object mapped = state == null ? null : blockStateIds.get(state);
                if (mapped instanceof Integer id) {
                    shaderBlockId = id;
                }
            }
            for (int vertex = 0; vertex < 4; vertex++) {
                MemoryUtil.memPutShort(
                    firstVertex + (long) vertex * IrisCanonicalVertexData.STRIDE
                        + IrisCanonicalVertexData.BLOCK_ID_OFFSET,
                    (short) shaderBlockId
                );
            }
        }
    }

    private static float directionalShade(byte metadata) {
        Direction direction = IrisCanonicalVertexData.direction(metadata);
        if (direction == null) {
            return 1.0f;
        }
        Minecraft client = Minecraft.getInstance();
        return client.level == null ? 1.0f : client.level.getShade(
            direction,
            IrisCanonicalVertexData.shade(metadata)
        );
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
            }
        }
    }

    private static final class IrisState {

        private final Field sharedVertexType;
        private final Field renderingSettings;
        private final Method getBlockStateIds;
        private final Method shouldDisableDirectionalShading;
        private final Method getAmbientOcclusionLevel;
        private final Method shouldVoxelizeLightBlocks;
        private final Method getBlockTypeIds;
        private final Method getCurrentPackName;

        private IrisState(
            Field sharedVertexType,
            Field renderingSettings,
            Method getBlockStateIds,
            Method shouldDisableDirectionalShading,
            Method getAmbientOcclusionLevel,
            Method shouldVoxelizeLightBlocks,
            Method getBlockTypeIds,
            Method getCurrentPackName
        ) {
            this.sharedVertexType = sharedVertexType;
            this.renderingSettings = renderingSettings;
            this.getBlockStateIds = getBlockStateIds;
            this.shouldDisableDirectionalShading = shouldDisableDirectionalShading;
            this.getAmbientOcclusionLevel = getAmbientOcclusionLevel;
            this.shouldVoxelizeLightBlocks = shouldVoxelizeLightBlocks;
            this.getBlockTypeIds = getBlockTypeIds;
            this.getCurrentPackName = getCurrentPackName;
        }

        private static IrisState create() {
            try {
                Class<?> formats = Class.forName(
                    "net.irisshaders.iris.compat.sodium.impl.vertex_format.IrisModelVertexFormats"
                );
                Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
                Class<?> settings = Class.forName("net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings");
                return new IrisState(
                    formats.getField("MODEL_VERTEX_XHFP"),
                    settings.getField("INSTANCE"),
                    settings.getMethod("getBlockStateIds"),
                    settings.getMethod("shouldDisableDirectionalShading"),
                    settings.getMethod("getAmbientOcclusionLevel"),
                    settings.getMethod("shouldVoxelizeLightBlocks"),
                    settings.getMethod("getBlockTypeIds"),
                    iris.getMethod("getCurrentPackName")
                );
            } catch (ReflectiveOperationException | LinkageError exception) {
                return new IrisState(null, null, null, null, null, null, null, null);
            }
        }

        private ChunkVertexType sharedVertexType(ChunkVertexType fallback) {
            if (this.sharedVertexType != null) {
                try {
                    return (ChunkVertexType) this.sharedVertexType.get(null);
                } catch (ReflectiveOperationException | LinkageError exception) {
                }
            }
            return fallback;
        }

        private int sharedVertexStride(int fallback) {
            ChunkVertexType vertexType = this.sharedVertexType(null);
            return vertexType == null ? fallback : vertexType.getVertexFormat().getStride();
        }

        private Map<?, ?> blockStateIds() {
            if (this.renderingSettings != null) {
                try {
                    Object settings = this.renderingSettings.get(null);
                    Object ids = this.getBlockStateIds.invoke(settings);
                    if (ids instanceof Map<?, ?> map) {
                        return map;
                    }
                } catch (ReflectiveOperationException | LinkageError exception) {
                }
            }
            return null;
        }

        private boolean shouldDisableDirectionalShading() {
            if (this.renderingSettings != null) {
                try {
                    Object settings = this.renderingSettings.get(null);
                    return (boolean) this.shouldDisableDirectionalShading.invoke(settings);
                } catch (ReflectiveOperationException | LinkageError exception) {
                }
            }
            return false;
        }

        private float ambientOcclusionLevel() {
            if (this.renderingSettings != null) {
                try {
                    Object settings = this.renderingSettings.get(null);
                    return (float) this.getAmbientOcclusionLevel.invoke(settings);
                } catch (ReflectiveOperationException | LinkageError exception) {
                }
            }
            return 1.0f;
        }

        private int geometryFingerprint() {
            if (this.renderingSettings == null) {
                return 0;
            }
            try {
                Object settings = this.renderingSettings.get(null);
                int result = Boolean.hashCode((boolean) this.shouldVoxelizeLightBlocks.invoke(settings));
                Object blockTypes = this.getBlockTypeIds.invoke(settings);
                if (blockTypes instanceof Map<?, ?> map && !map.isEmpty()) {
                    Object packName = this.getCurrentPackName.invoke(null);
                    result = 31 * result + (packName == null ? map.size() : packName.hashCode());
                } else {
                    result = 31 * result;
                }
                return result;
            } catch (ReflectiveOperationException | LinkageError exception) {
                return 0;
            }
        }
    }
}
