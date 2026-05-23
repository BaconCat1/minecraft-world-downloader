package config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigTest {
    @Test
    void parseSeedDefaultsBlankToZero() {
        assertThat(Config.parseSeed(null)).isZero();
        assertThat(Config.parseSeed("")).isZero();
        assertThat(Config.parseSeed("   ")).isZero();
    }

    @Test
    void parseSeedAcceptsNumericValues() {
        assertThat(Config.parseSeed("1234567890123")).isEqualTo(1234567890123L);
        assertThat(Config.parseSeed(" -42 ")).isEqualTo(-42L);
    }

    @Test
    void parseSeedUsesMinecraftTextSeedHashing() {
        assertThat(Config.parseSeed("World Downloader")).isEqualTo("World Downloader".hashCode());
    }
}
