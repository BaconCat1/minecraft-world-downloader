package game.data.registries;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RegistryManagerTest {
    @AfterEach
    void afterEach() {
        RegistryManager.setInstance(null);
    }

    @Test
    void startsWithEmptyRegistriesUntilGeneratedRegistriesLoad() {
        RegistryManager registryManager = RegistryManager.getInstance();

        assertThat(registryManager.getMenuRegistry()).isNotNull();
        assertThat(registryManager.getItemRegistry()).isNotNull();
        assertThat(registryManager.getBlockEntityRegistry()).isNotNull();
        assertThat(registryManager.getVillagerProfessionRegistry()).isNotNull();
        assertThat(registryManager.getVillagerTypeRegistry()).isNotNull();
    }
}
