package game.data.maps;

import java.util.ArrayList;
import java.util.Collection;
import packets.DataTypeProvider;

public class PlayerMap_1_21_11 extends PlayerMap_1_21 {
    public PlayerMap_1_21_11(int id) {
        super(id);
    }

    @Override
    public void parse(DataTypeProvider provider) {
        byte scale = provider.readNext();
        if (scale != 0) {
            this.scale = scale;
        }

        this.locked = provider.readBoolean();

        this.icons = new ArrayList<>();
        boolean hasIcons = provider.readBoolean();
        if (hasIcons) {
            readIcons(provider, this.icons);
        }

        readMapImage_1_21_11(provider);
    }

    private void readIcons(DataTypeProvider provider, Collection<Icon> icons) {
        int iconCount = provider.readVarInt();
        for (int i = 0; i < iconCount; i++) {
            Icon icon = Icon.of(provider);
            if (icon != null) {
                icons.add(icon);
            }
        }
    }

    private void readMapImage_1_21_11(DataTypeProvider provider) {
        int columns = provider.readNext() & 0xFF;
        if (columns == 0) {
            return;
        }

        int rows = provider.readNext() & 0xFF;
        int firstCol = provider.readNext() & 0xFF;
        int firstRow = provider.readNext() & 0xFF;
        int length = columns * rows;
        byte[] colUpdate = provider.readByteArray(length);

        if (rows == 128 && columns == 128) {
            this.colors = colUpdate;
            return;
        }

        if (this.colors == null || this.colors.length != 128 * 128) {
            this.colors = new byte[128 * 128];
        }

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int src = col + row * columns;
                if (src >= colUpdate.length) {
                    return;
                }

                int curRow = firstRow + row;
                int curCol = firstCol + col;
                if (curRow >= 0 && curRow < 128 && curCol >= 0 && curCol < 128) {
                    this.colors[curCol + curRow * 128] = colUpdate[src];
                }
            }
        }
    }

}
