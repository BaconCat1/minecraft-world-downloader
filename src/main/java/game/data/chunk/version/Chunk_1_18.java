package game.data.chunk.version;

import config.Config;
import config.Version;
import game.data.chunk.BlockEntityRegistry;
import game.data.chunk.ChunkSection;
import game.data.chunk.palette.BlockState;
import game.data.chunk.palette.BlockRegistry;
import game.data.chunk.palette.GlobalPaletteProvider;
import game.data.chunk.palette.Palette;
import game.data.chunk.palette.PaletteType;
import game.data.coordinates.Coordinate3D;
import game.data.coordinates.CoordinateDim2D;
import game.data.registries.RegistryManager;
import game.protocol.Protocol;
import packets.DataTypeProvider;
import packets.builder.PacketBuilder;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.IntTag;
import se.llbit.nbt.ListTag;
import se.llbit.nbt.LongArrayTag;
import se.llbit.nbt.NamedTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.StringTag;
import se.llbit.nbt.Tag;

public class Chunk_1_18 extends Chunk_1_17 {
    public Chunk_1_18(CoordinateDim2D location, int version) {
        super(location, version);
    }

    @Override
    public ChunkSection createNewChunkSection(byte y, Palette palette) {
        return new ChunkSection_1_18(y, palette, this);
    }

    @Override
    protected ChunkSection parseSection(int sectionY, SpecificTag section) {
        return new ChunkSection_1_18(sectionY, section, this);
    }

    /**
     * Parse the chunk data.
     *
     * @param dataProvider network input
     */
    @Override
    protected void parse(DataTypeProvider dataProvider) {
        raiseEvent("parse from packet");
        DataTypeProvider provider = dataProvider;

        parseHeightMaps(provider);

        if (Config.versionReporter().isAtLeast(Version.V1_21_11)) {        }

        int size = provider.readVarInt();

        try {
            readChunkColumn(provider.ofLength(size));

            parseBlockEntities(provider);

            updateLight(provider);
        } catch (Exception ex) {
            // seems to happen when there's blocks above 192 under some conditions
            System.out.println("Issue parse chunk at " + location + ". Cause: " + ex.getMessage());
            ex.printStackTrace();
        }

        afterParse();
    }

    @Override
    protected void parseHeightMaps(DataTypeProvider dataProvider) {
        if (Config.versionReporter().isAtLeast(Version.V1_21_11)) {
            heightMap = readHeightmapsMap(dataProvider);            return;
        }

        SpecificTag tag = dataProvider.readNbtTag();
        heightMap = tag;
    }

    private CompoundTag readHeightmapsMap(DataTypeProvider dataProvider) {
        CompoundTag map = new CompoundTag();

        int count = dataProvider.readVarInt();
        for (int i = 0; i < count; i++) {
            int type = dataProvider.readVarInt();
            int longCount = dataProvider.readVarInt();
            long[] data = dataProvider.readLongArray(longCount);

            map.add(heightmapName(type), new LongArrayTag(data));
        }

        return map;
    }

    private String heightmapName(int type) {
        return switch (type) {
            case 0 -> "WORLD_SURFACE_WG";
            case 1 -> "WORLD_SURFACE";
            case 2 -> "OCEAN_FLOOR_WG";
            case 3 -> "OCEAN_FLOOR";
            case 4 -> "MOTION_BLOCKING";
            case 5 -> "MOTION_BLOCKING_NO_LEAVES";
            default -> "UNKNOWN_" + type;
        };
    }

    private ProviderSelection selectProviderForChunk(DataTypeProvider provider) {
        DataTypeProvider withHeightmaps = provider.copyAtPosition();
        if (hasValidChunkSizeAfterHeightmaps(withHeightmaps)) {
            return new ProviderSelection(withHeightmaps, false);
        }

        DataTypeProvider withoutHeightmaps = provider.copyAtPosition();
        int size = withoutHeightmaps.readVarInt();
        if (isValidChunkSize(size, withoutHeightmaps)) {
            return new ProviderSelection(withoutHeightmaps, true);
        }

        return new ProviderSelection(provider, false);
    }

    private boolean hasValidChunkSizeAfterHeightmaps(DataTypeProvider provider) {
        try {
            provider.readNbtTag();
            int size = provider.readVarInt();
            return isValidChunkSize(size, provider);
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isValidChunkSize(int size, DataTypeProvider provider) {
        return size >= 0 && size <= provider.remaining();
    }

    private record ProviderSelection(DataTypeProvider provider, boolean skipHeightMaps) {}

    private void logChunkPacketState(String label, DataTypeProvider provider) {
        DataTypeProvider peek = provider.copyAtPosition();
        int peekCount = Math.min(16, peek.remaining());
        byte[] bytes = peek.readByteArray(peekCount);

        System.out.println(
            "Chunk packet " + label +
                " pos=" + provider.position() +
                " remaining=" + provider.remaining() +
                " length=" + provider.length() +
                " next=" + toHex(bytes)
        );
    }

    private String toHex(byte[] bytes) {
        if (bytes.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02x ", b));
        }
        return sb.toString().trim();
    }

    private String summarizeHeightmaps(SpecificTag tag) {
        if (tag == null) {
            return "null";
        }
        if (tag instanceof CompoundTag compound) {
            StringBuilder sb = new StringBuilder("CompoundTag keys=");
            int count = 0;
            sb.append("[");
            for (NamedTag named : compound) {
                if (count > 0) {
                    sb.append(",");
                }
                sb.append(named.name);
                count++;
                if (count >= 6) {
                    break;
                }
            }
            if (compound.iterator().hasNext() && count >= 6) {
                sb.append(",...");
            }
            sb.append("]");
            return sb.toString();
        }
        return tag.getClass().getSimpleName();
    }

    /**
     * Generate network packet for this chunk.
     */
    @Override
    public PacketBuilder toPacket() {
        Protocol p = Config.versionReporter().getProtocol();
        PacketBuilder packet = new PacketBuilder();
        packet.writeVarInt(p.clientBound("LevelChunkWithLight"));

        packet.writeInt(location.getX());
        packet.writeInt(location.getZ());

        writeHeightMaps(packet);
        writeChunkSections(packet);

        // we don't include block entities - these chunks will be far away so they shouldn't be rendered anyway
        packet.writeVarInt(0);

        writeLightEdgesTrusted(packet);
        writeLightToPacket(packet);

        return packet;
    }

    @Override
    public PacketBuilder toLightPacket() {
        return null;
    }


    /**
     * Read a chunk column for 1.18
     */
    public void readChunkColumn(DataTypeProvider dataProvider) {
        // Loop through section Y values, starting from the lowest section that has blocks inside it.
        for (int sectionY = getMinBlockSection(); sectionY <= getMaxBlockSection() && dataProvider.hasNext(); sectionY++) {
            ChunkSection_1_18 section = (ChunkSection_1_18) getChunkSection(sectionY);

            int blockCount = dataProvider.readShort();
            Palette blockPalette = Palette.readPalette(dataProvider, PaletteType.BLOCKS);

            if (section == null) {
                section = (ChunkSection_1_18) createNewChunkSection((byte) (sectionY & 0xFF), blockPalette);
            } else {
                section.setBlockPalette(blockPalette);
            }

            section.setBlockCount(blockCount);

            int longsExpectedBlocks = ChunkSection_1_18.longsRequired(blockPalette.getBitsPerBlock());
            section.setBlocks(dataProvider.readLongArray(longsExpectedBlocks));

            Palette biomePalette = Palette.readPalette(dataProvider, PaletteType.BIOMES);
            section.setBiomePalette(biomePalette);

            int longsExpectedBiomes = ChunkSection_1_18.longsRequiredBiomes(biomePalette.getBitsPerBlock());
            section.setBiomes(dataProvider.readLongArray(longsExpectedBiomes));

            // May replace an existing section or a null one
            setChunkSection(sectionY, section);

            // servers don't (always?) include containers in the list of block_entities. We need to know that these block
            // entities exist, otherwise we'll end up not writing block entity data for them
            if (containsBlockEntities(blockPalette)) {
                findBlockEntities(section, sectionY);
            }
        }
    }

    protected void findBlockEntities(ChunkSection section, int sectionY) {
        BlockEntityRegistry blockEntities = RegistryManager.getInstance().getBlockEntityRegistry();
        BlockRegistry globalPalette = GlobalPaletteProvider.getGlobalPalette(getDataVersion());

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = globalPalette.getState(section.getNumericBlockStateAt(x, y, z));

                    if (blockEntities.isBlockEntity(state.getName())) {
                        Coordinate3D coords = new Coordinate3D(x, y, z).sectionLocalToGlobal(sectionY, this.location);
                        this.addBlockEntity(coords, this.generateBlockEntity(state.getName(), coords));
                    }
                }
            }
        }
    }

    protected boolean containsBlockEntities(Palette p) {
        BlockEntityRegistry blockEntities = RegistryManager.getInstance().getBlockEntityRegistry();
        for (SpecificTag tag : p.toNbt()) {
            if (blockEntities.isBlockEntity(tag.get("Name").stringValue())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void parseBlockEntities(DataTypeProvider dataProvider) {
        int blockEntityCount = dataProvider.readVarInt();
        for (int i = 0; i < blockEntityCount; i++) {
            byte xz = dataProvider.readNext();
            int x = xz >> 4;
            int z = xz & 0b1111;
            int y = dataProvider.readShort();
            int type = dataProvider.readVarInt();

            // Get the exact coordinates in the world
            x = (this.getLocation().getX() * 16) + x;
            z = (this.getLocation().getZ() * 16) + z;

            SpecificTag tag = dataProvider.readNbtTag();
            if (tag instanceof CompoundTag entity) {
                String blockEntityID = RegistryManager.getInstance().getBlockEntityRegistry().getBlockEntityName(type);

                entity.add("id", new StringTag(blockEntityID));
                addBlockEntity(new Coordinate3D(x, y, z), entity);
            }
        }
    }

    /**
     * Convert this chunk to NBT tags.
     *
     * @return the nbt root tag
     */
    public NamedTag toNbt() {
        if (!hasSections()) {
            return null;
        }

        CompoundTag root = new CompoundTag();

        addLevelNbtTags(root);
        root.add("DataVersion", new IntTag(getDataVersion()));

        return new NamedTag("", root);
    }

    @Override
    protected void addLevelNbtTags(CompoundTag map) {
        addGeneralLevelTags(map);
        map.add("yPos", new IntTag(getMinBlockSection()));

        map.add("Heightmaps", heightMap);
        map.add("Status", new StringTag("full"));

        CompoundTag structures = new CompoundTag();
        structures.add("References", new CompoundTag());
        structures.add("Starts", new CompoundTag());
        map.add("Structures", structures);

        map.add("sections", new ListTag(Tag.TAG_COMPOUND, getSectionList()));

        addBlockEntities(map);
    }

    @Override
    public void parse(Tag tag) {
        raiseEvent("parse from nbt");

        tag.asCompound().get("sections").asList().forEach(section -> {
            int sectionY = section.get("Y").byteValue();
            setChunkSection(sectionY, parseSection(sectionY, section));
        });
        parseHeightMaps(tag);
    }

    @Override
    protected void parseHeightMaps(Tag tag) {
        heightMap = tag.asCompound().get("Heightmaps").asCompound();
    }

    @Override
    protected void parseBiomes(Tag tag) {
        Tag biomeTag = tag.asCompound().get("Biomes");
        setBiomes(biomeTag.intArray());
    }


}
