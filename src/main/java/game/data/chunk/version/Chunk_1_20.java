package game.data.chunk.version;

import config.Config;
import config.Version;
import game.data.chunk.ChunkSection;
import game.data.chunk.palette.Palette;
import game.data.coordinates.CoordinateDim2D;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import packets.DataTypeProvider;
import packets.builder.PacketBuilder;

public class Chunk_1_20 extends Chunk_1_18 {
    public Chunk_1_20(CoordinateDim2D location, int version) {
        super(location, version);
    }

    /**
     * Trusted edges parameter for lighting on chunk edges was removed in 1.20
     */
    @Override
    void parseLightEdgesTrusted(DataTypeProvider provider) {

    }

    @Override
    void writeLightEdgesTrusted(PacketBuilder packet) {

    }

    @Override
    public void updateLight(DataTypeProvider provider) {
        if (Config.versionReporter().isAtLeast(Version.V1_21_11)) {
            BitSet skyLightMask = provider.readBitSet();
            BitSet blockLightMask = provider.readBitSet();

            BitSet emptySkyLightMask = provider.readBitSet();
            BitSet emptyBlockLightMask = provider.readBitSet();

            int skyUpdateCount = provider.readVarInt();
            List<byte[]> skyUpdates = new ArrayList<>(skyUpdateCount);
            for (int i = 0; i < skyUpdateCount; i++) {
                skyUpdates.add(provider.readPrefixedByteArray());
            }

            int blockUpdateCount = provider.readVarInt();
            List<byte[]> blockUpdates = new ArrayList<>(blockUpdateCount);
            for (int i = 0; i < blockUpdateCount; i++) {
                blockUpdates.add(provider.readPrefixedByteArray());
            }

            applyLightUpdates(skyLightMask, emptySkyLightMask, skyUpdates, ChunkSection::setSkyLight, ChunkSection::getSkyLight);
            applyLightUpdates(blockLightMask, emptyBlockLightMask, blockUpdates, ChunkSection::setBlockLight, ChunkSection::getBlockLight);
            return;
        }

        super.updateLight(provider);
    }

    private void applyLightUpdates(
        BitSet mask,
        BitSet emptyMask,
        List<byte[]> updates,
        BiConsumer<ChunkSection, byte[]> setter,
        Function<ChunkSection, byte[]> getter
    ) {
        Iterator<byte[]> updateIterator = updates.iterator();

        for (int sectionY = getMinLightSection(); sectionY <= getMaxLightSection() && (!mask.isEmpty() || !emptyMask.isEmpty()); sectionY++) {
            ChunkSection section = getChunkSection(sectionY);
            if (section == null) {
                section = createNewChunkSection((byte) sectionY, Palette.empty());
                section.setBlocks(new long[256]);
                setChunkSection(sectionY, section);
            }

            int bitIndex = sectionY - getMinLightSection();
            if (!mask.get(bitIndex)) {
                if (!emptyMask.get(bitIndex)) {
                    setter.accept(section, new byte[2048]);
                }
                emptyMask.set(bitIndex, false);
                continue;
            }

            mask.set(bitIndex, false);
            setter.accept(section, updateIterator.next());
        }
    }
}
