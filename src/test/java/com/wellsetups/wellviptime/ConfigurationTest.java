package com.wellsetups.wellviptime;

import static org.junit.jupiter.api.Assertions.*;

import com.wellsetups.wellviptime.configuration.ConfigError;
import com.wellsetups.wellviptime.configuration.ConfigLoader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

class ConfigurationTest {

    @TempDir Path directory;

    @Test
    void olderConfigEnablesGroupProvisioningWithoutOverwritingFile() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, material -> true);
        loader.load();
        Path file = directory.resolve("config.yml");
        String older = Files.readString(file).replace("  create-missing-groups: true", "");
        Files.writeString(file, older);
        assertTrue(loader.load().settings().core().createMissingGroups());
        assertEquals(older, Files.readString(file));
    }

    @Test
    void bundledConfigurationLoadsAndPreservesEdits() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, material -> true);
        var first = loader.load();
        assertEquals(3, first.settings().types().size());
        assertTrue(first.languages().raw("en_US", "error.duration").contains("30d"));
        Path file = directory.resolve("commands.yml");
        Files.writeString(
                file, Files.readString(file).replace("name: viptime", "name: premiumtime"));
        assertEquals("premiumtime", loader.load().settings().commands().get("viptime").name());
    }

    @Test
    void invalidConfigHasLocationAndExpectation() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, material -> true);
        loader.load();
        Path file = directory.resolve("config.yml");
        Files.writeString(file, Files.readString(file).replace("page-size: 10", "page-size: -1"));
        var error = assertThrows(ConfigError.class, loader::load);
        assertTrue(error.getMessage().contains("config.yml :: listing.page-size = -1"));
    }

    @Test
    void duplicateAliasesAndUnsafeUrlsAreRejectedWithoutLeakingSecrets() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, material -> true);
        loader.load();
        Path commands = directory.resolve("commands.yml");
        String original = Files.readString(commands);
        Files.writeString(commands, original.replace("aliases: [vtime]", "aliases: [viptime]"));
        assertTrue(
                assertThrows(ConfigError.class, loader::load)
                        .getMessage()
                        .contains("unique lowercase"));
        Files.writeString(commands, original);
        Path discord = directory.resolve("discord.yml");
        Files.writeString(
                discord,
                Files.readString(discord)
                        .replace("enabled: false", "enabled: true")
                        .replace(
                                "webhook-url: ''",
                                "webhook-url: 'https://attacker.example/secret-token'"));
        var failure = assertThrows(ConfigError.class, loader::load);
        assertFalse(failure.getMessage().contains("secret-token"));
        assertTrue(failure.getMessage().contains("<redacted>"));
    }

    @Test
    void dynamicVipDefinitionsAreNotRestoredFromBundledExamples() throws Exception {
        var loader = new ConfigLoader(directory, ignored -> {}, material -> true);
        loader.load();
        Files.writeString(
                directory.resolve("vip-types.yml"),
                "vip-types:\n"
                        + "  supporter:\n"
                        + "    display-name: '<green>Supporter'\n"
                        + "    luckperms-group: supporter\n"
                        + "    priority: 1\n");
        assertEquals(java.util.Set.of("supporter"), loader.load().settings().types().keySet());
    }
}
