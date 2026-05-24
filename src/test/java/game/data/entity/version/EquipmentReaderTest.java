package game.data.entity.version;

import game.data.container.Slot;
import org.junit.jupiter.api.Test;
import packets.DataTypeProvider;
import packets.version.DataTypeProvider_1_20_6;

import static org.assertj.core.api.Assertions.assertThat;

public class EquipmentReaderTest {
    @Test
    void supportsNewerEquipmentSlotIds() {
        DataTypeProvider provider = new DataTypeProvider_1_20_6(new byte[] { 6, 0 });

        Slot[] equipment = new EquipmentReader_1_15().readSlots(new Slot[6], provider);

        assertThat(equipment).hasSize(7);
        assertThat(provider.hasNext()).isFalse();
    }
}
