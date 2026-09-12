package net.fly.insulin.cache;

import me.jellysquid.mods.sodium.client.world.cloned.ChunkRenderContext;
import me.jellysquid.mods.sodium.client.world.cloned.ClonedChunkSection;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

public record SectionFingerprint(long low, long high) {

    private static final int AIR_STATE_ID = Block.getId(Blocks.AIR.defaultBlockState());

    public static SectionFingerprint compute(ChunkRenderContext context) {
        Hasher hasher = new Hasher();
        BoundingBox volume = context.getVolume();
        SectionPos origin = context.getOrigin();
        ClonedChunkSection[] sections = context.getSections();

        hasher.putInt(volume.minX());
        hasher.putInt(volume.minY());
        hasher.putInt(volume.minZ());
        hasher.putInt(volume.maxX());
        hasher.putInt(volume.maxY());
        hasher.putInt(volume.maxZ());
        hasher.putInt(net.fly.insulin.InsulinCommon.restorer().stateFingerprint());

        for (int y = volume.minY(); y <= volume.maxY(); y++) {
            for (int z = volume.minZ(); z <= volume.maxZ(); z++) {
                for (int x = volume.minX(); x <= volume.maxX(); x++) {
                    int localX = x & 15;
                    int localY = y & 15;
                    int localZ = z & 15;
                    int sectionX = SectionPos.blockToSectionCoord(x) - origin.x() + 1;
                    int sectionY = SectionPos.blockToSectionCoord(y) - origin.y() + 1;
                    int sectionZ = SectionPos.blockToSectionCoord(z) - origin.z() + 1;
                    if (sectionX < 0 || sectionX > 2 || sectionY < 0 || sectionY > 2
                        || sectionZ < 0 || sectionZ > 2) {
                        return null;
                    }
                    int sectionIndex = sectionY * 9 + sectionZ * 3 + sectionX;
                    if (sectionIndex >= sections.length) {
                        return null;
                    }
                    ClonedChunkSection section = sections[sectionIndex];
                    if (section == null) {
                        return null;
                    }
                    PalettedContainerRO<BlockState> blockData = section.getBlockData();
                    Holder<Biome> biome = section.getBiomeData() == null
                        ? null
                        : section.getBiomeData().get(localX >> 2, localY >> 2, localZ >> 2);
                    var blockLight = section.getLightArray(LightLayer.BLOCK);
                    var skyLight = section.getLightArray(LightLayer.SKY);
                    int biomeId = biome == null ? Biomes.PLAINS.location().hashCode() : biomeId(biome);

                    hasher.putInt(blockData == null ? AIR_STATE_ID : Block.getId(blockData.get(
                        localX,
                        localY,
                        localZ
                    )));
                    hasher.putInt(blockLight == null ? 0 : blockLight.get(localX, localY, localZ));
                    hasher.putInt(skyLight == null ? 0 : skyLight.get(localX, localY, localZ));
                    hasher.putInt(biomeId);
                    var renderData = section.getBlockEntityRenderDataMap();
                    if (renderData != null) {
                        Object value = renderData.get((localY << 8) | (localZ << 4) | localX);
                        if (value != null) {
                            hasher.putInt(value.getClass().getName().hashCode());
                            hasher.putInt(renderDataHash(value));
                        }
                    }
                }
            }
        }

        return hasher.finish();
    }

    private static int renderDataHash(Object value) {
        try {
            return value.hashCode();
        } catch (RuntimeException exception) {
            return value.getClass().getName().hashCode();
        }
    }

    private static int biomeId(Holder<Biome> biome) {
        return biome.unwrapKey().map(key -> key.location().hashCode()).orElseGet(() -> {
            Biome value = biome.value();
            int result = Float.floatToIntBits(value.getBaseTemperature());
            result = 31 * result + value.getSpecialEffects().getFogColor();
            result = 31 * result + value.getSpecialEffects().getWaterColor();
            result = 31 * result + value.getSpecialEffects().getWaterFogColor();
            result = 31 * result + value.getSpecialEffects().getSkyColor();
            result = 31 * result + value.getSpecialEffects().getFoliageColorOverride().orElse(0);
            result = 31 * result + value.getSpecialEffects().getGrassColorOverride().orElse(0);
            return result;
        });
    }

    private static final class Hasher {

        private long low = 0x243f6a8885a308d3L;
        private long high = 0x13198a2e03707344L;
        private long count;

        private void putInt(int value) {
            long input = Integer.toUnsignedLong(value);
            this.low ^= input + 0x9e3779b97f4a7c15L + (this.low << 6) + (this.low >>> 2);
            this.low = Long.rotateLeft(this.low * 0xbf58476d1ce4e5b9L, 27);
            this.high += input * 0x94d049bb133111ebL;
            this.high = Long.rotateLeft(this.high ^ this.low, 31) * 0x9e3779b97f4a7c15L;
            this.count++;
        }

        private SectionFingerprint finish() {
            long finalLow = avalanche(this.low ^ this.count);
            long finalHigh = avalanche(this.high ^ Long.rotateLeft(this.count, 23) ^ finalLow);
            return new SectionFingerprint(finalLow, finalHigh);
        }

        private static long avalanche(long value) {
            value ^= value >>> 30;
            value *= 0xbf58476d1ce4e5b9L;
            value ^= value >>> 27;
            value *= 0x94d049bb133111ebL;
            return value ^ value >>> 31;
        }
    }
}
