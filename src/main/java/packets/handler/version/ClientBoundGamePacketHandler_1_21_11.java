package packets.handler.version;

import game.data.WorldManager;
import game.data.coordinates.Coordinate3D;
import game.data.entity.EntityRegistry;
import game.data.entity.ObjectEntity;
import game.data.registries.RegistryManager;
import java.util.Map;
import packets.handler.PacketOperator;
import proxy.ConnectionManager;
import se.llbit.nbt.CompoundTag;
import se.llbit.nbt.SpecificTag;
import se.llbit.nbt.StringTag;

public class ClientBoundGamePacketHandler_1_21_11 extends ClientBoundGamePacketHandler_1_20_6 {
    public ClientBoundGamePacketHandler_1_21_11(ConnectionManager connectionManager) {
        super(connectionManager);

        WorldManager worldManager = WorldManager.getInstance();
        EntityRegistry entityRegistry = worldManager.getEntityRegistry();
        Map<String, PacketOperator> operators = getOperators();

        operators.put("AddEntity", provider -> {
            entityRegistry.addEntity(provider, ObjectEntity::parse);
            return true;
        });

        operators.put("PlayerInfoUpdate", provider -> true);

        operators.put("PlayerInfoRemove", provider -> {
            entityRegistry.removePlayers(provider);
            return true;
        });

        operators.put("OpenScreen", provider -> {
            try {
                int windowId = provider.readVarInt();
                int windowType = provider.readVarInt();
                String windowTitle = provider.readChat();

                worldManager.getContainerManager().openWindow(windowId, windowType, windowTitle);
            } catch (RuntimeException ex) {
                return true;
            }
            return true;
        });

        operators.put("BlockEntityData", provider -> {
            Coordinate3D position = provider.readCoordinates();
            int type = provider.readVarInt();
            SpecificTag entityData = provider.readNbtTag();

            if (entityData instanceof CompoundTag entity) {
                entity.add("id", new StringTag(RegistryManager.getInstance().getBlockEntityRegistry().getBlockEntityName(type)));
            }
            worldManager.getChunkFactory().updateTileEntity(position, entityData);
            return true;
        });
    }
}
