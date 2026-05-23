package game.data.maps;

import packets.DataTypeProvider;

public class PlayerMap_1_21 extends PlayerMap_1_17 {
    public PlayerMap_1_21(int id) {
        super(id);
    }

    @Override
    public void parse(DataTypeProvider provider) {
        byte scale = provider.readNext();
        if (scale != 0) {
            this.scale = scale;
        }

        this.locked = provider.readBoolean();
        parseIcons(provider);

        boolean hasColorPatch = provider.readBoolean();
        if (hasColorPatch) {
            parseMapImage_1_21(provider);
        }
    }

    private void parseMapImage_1_21(DataTypeProvider provider) {
        int columns = provider.readNext() & 0xFF;
        if (columns == 0) {
            return;
        }

        int rows = provider.readNext() & 0xFF;
        int firstCol = provider.readNext() & 0xFF;
        int firstRow = provider.readNext() & 0xFF;
        byte[] colUpdate = provider.readByteArray(columns * rows);

        if (rows == 128 && columns == 128) {
            this.colors = colUpdate;
            return;
        }

        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < columns; col++) {
                int curRow = firstRow + row;
                int curCol = firstCol + col;
                this.colors[curCol + curRow * 128] = colUpdate[col + row * columns];
            }
        }
    }
}
