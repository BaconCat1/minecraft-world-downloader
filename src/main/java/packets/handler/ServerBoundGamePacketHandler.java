package packets.handler;

import config.Version;
import game.NetworkMode;
import java.util.HashMap;
import java.util.Map;

import config.Config;
import game.data.WorldManager;
import game.data.coordinates.Coordinate3D;
import packets.DataTypeProvider;
import proxy.ConnectionManager;

public class ServerBoundGamePacketHandler extends PacketHandler {
    private HashMap<String, PacketOperator> operations = new HashMap<>();
    public ServerBoundGamePacketHandler(ConnectionManager connectionManager) {
        super(connectionManager);

        PacketOperator updatePlayerPosition = provider -> {
            if (provider.remaining() >= 24) {
                double x = provider.readDouble();
                double y = provider.readDouble();
                double z = provider.readDouble();
                WorldManager.getInstance().setPlayerPosition(x, y, z);
            }

            readMovementFlags(provider);

            return true;
        };

        PacketOperator updatePlayerRotation = provider -> {
            if (provider.remaining() >= 8) {
                double yaw = provider.readFloat() % 360;
                provider.readFloat(); // pitch
                WorldManager.getInstance().setPlayerRotation(yaw);
            }

            readMovementFlags(provider);
            return true;
        };

        operations.put("MovePlayerPos", updatePlayerPosition);
        operations.put("MovePlayerRot", updatePlayerRotation);
        operations.put("MovePlayerStatusOnly", provider -> {
            readMovementFlags(provider);
            return true;
        });
        operations.put("MovePlayerPosRot", (provider) -> {
            if (provider.remaining() >= 32) {
                double x = provider.readDouble();
                double y = provider.readDouble();
                double z = provider.readDouble();
                double yaw = provider.readFloat() % 360;
                provider.readFloat(); // pitch

                WorldManager.getInstance().setPlayerPosition(x, y, z);
                WorldManager.getInstance().setPlayerRotation(yaw);
                readMovementFlags(provider);
                return true;
            }

            if (provider.remaining() >= 24) {
                return updatePlayerPosition.apply(provider);
            }

            if (provider.remaining() >= 8) {
                return updatePlayerRotation.apply(provider);
            }

            readMovementFlags(provider);
            return true;
        });

        operations.put("MoveVehicle", provider -> {
            if (provider.remaining() >= 24) {
                double x = provider.readDouble();
                double y = provider.readDouble();
                double z = provider.readDouble();
                WorldManager.getInstance().setPlayerPosition(x, y, z);
            }

            if (provider.remaining() >= 8) {
                double yaw = provider.readFloat() % 360;
                provider.readFloat(); // pitch
                WorldManager.getInstance().setPlayerRotation(yaw);
            }
            return true;
        });

        operations.put("UseItem", provider -> {
            // newer versions first include a VarInt with the hand
            if (Config.versionReporter().isAtLeast(Version.V1_14)) {
                provider.readVarInt();
            }
            if (Config.versionReporter().isAtLeast(Version.V1_21)) {
                provider.readVarInt(); // sequence
                provider.readFloat();  // yaw
                provider.readFloat();  // pitch
            }

            return true;
        });

        operations.put("ContainerClose", provider -> {
            final byte windowId = provider.readNext();
            WorldManager.getInstance().getContainerManager().closeWindow(windowId);
            WorldManager.getInstance().getVillagerManager().closeWindow(windowId);
            return true;
        });

        // block placements
        operations.put("UseItemOn", provider -> {
            provider.readVarInt();  // Hand
            Coordinate3D coords = provider.readCoordinates();
            provider.readVarInt();  // Block face
            provider.readFloat();   // Cursor x
            provider.readFloat();   // Cursor y
            provider.readFloat();   // Cursor z
            provider.readBoolean(); // If the player's head is inside of a block
            if (Config.versionReporter().isAtLeast(Version.V1_21)) {
                provider.readBoolean(); // If the interaction hit the world border
                provider.readVarInt();  // Sequence
            }

            WorldManager.getInstance().getContainerManager().lastInteractedWith(coords);
            return true;
        });

        operations.put("SetCommandBlock", provider -> {
            WorldManager.getInstance().getCommandBlockManager().readAndStoreCommandBlock(provider);
            return true;
        });

        operations.put("Interact", provider -> {
            WorldManager.getInstance().getVillagerManager().lastInteractedWith(provider);
            return true;
        });

        operations.put("ConfigurationAcknowledged", provider ->{
            getConnectionManager().setMode(NetworkMode.CONFIGURATION);
            return true;
        });
    }

    @Override
    public Map<String, PacketOperator> getOperators() {
        return operations;
    }

    @Override
    public boolean isClientBound() {
        return false;
    }

    private void readMovementFlags(DataTypeProvider provider) {
        if (provider.remaining() > 0) {
            provider.readNext();
        }
    }
}

