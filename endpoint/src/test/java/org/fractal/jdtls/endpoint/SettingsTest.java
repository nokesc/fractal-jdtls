// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsTest {

    static JsonObject json(String text) {
        return Json.parseStrict(text).getAsJsonObject();
    }

    @Test
    void theFixedSettingsAreAddedWhereTheFileHasNone() throws Exception {
        JsonObject merged = Settings.withFixed(json("{\"java\": {\"format\": {\"tabSize\": 2}}}"), "test");
        assertEquals(json("{\"java\": {\"format\": {\"tabSize\": 2}, \"autobuild\": {\"enabled\": false},"
                + " \"configuration\": {\"updateBuildConfiguration\": \"disabled\"}}}"), merged);
        assertEquals(json("{\"java\": {\"autobuild\": {\"enabled\": false}, \"configuration\": {\"updateBuildConfiguration\": \"disabled\"}}}"),
                Settings.load(Optional.empty()));
    }

    @Test
    void theSameValuesAreAccepted() throws Exception {
        Settings.withFixed(json("{\"java\": {\"autobuild\": {\"enabled\": false}}}"), "test");
        Settings.withFixed(json("{\"java.autobuild.enabled\": false, \"java.configuration.updateBuildConfiguration\": \"disabled\"}"), "test");
    }

    @Test
    void anotherValueIsRefusedInEveryForm() {
        for (String settings : new String[] {
                "{\"java\": {\"autobuild\": {\"enabled\": true}}}",
                "{\"java.autobuild.enabled\": true}",
                "{\"java\": {\"autobuild.enabled\": \"false\"}}",
                "{\"java.autobuild\": {\"enabled\": null}}",
                "{\"java\": {\"configuration\": {\"updateBuildConfiguration\": \"automatic\"}}}",
                "{\"java\": {\"configuration.updateBuildConfiguration\": \"interactive\"}}"}) {
            StartupException e = assertThrows(StartupException.class, () -> Settings.withFixed(json(settings), "the settings file f"), settings);
            assertTrue(e.getMessage().startsWith("the settings file f sets java."), e.getMessage());
            assertTrue(e.getMessage().contains("but HTTP mode fixes it to"), e.getMessage());
        }
    }

    @Test
    void aSettingThatCantHoldTheFixedOneIsRefused() {
        StartupException e = assertThrows(StartupException.class, () -> Settings.withFixed(json("{\"java\": \"x\"}"), "f"));
        assertTrue(e.getMessage().contains("must be an object"), e.getMessage());
    }

    @Test
    void lookupFindsNestedAndDottedSections() {
        JsonObject s = json("{\"java\": {\"format\": {\"tabSize\": 2}, \"format.insertSpaces\": true}}");
        assertEquals("2", Settings.lookup(s, "java.format.tabSize").toString());
        assertEquals("true", Settings.lookup(s, "java.format.insertSpaces").toString());
        assertEquals("{\"tabSize\":2}", Settings.lookup(s, "java.format").toString());
        assertTrue(Settings.lookup(s, "java.home").isJsonNull());
    }

    @Test
    void aFileThatIsntAJsonObjectIsRefused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("settings.json");
        for (String text : new String[] {"[]", "{\"java\": }", "{java: {}}", "{} {}", ""}) {
            Files.writeString(file, text);
            StartupException e = assertThrows(StartupException.class, () -> Settings.load(Optional.of(file)), text);
            assertTrue(e.getMessage().startsWith("the settings file " + file), e.getMessage());
        }
        assertThrows(StartupException.class, () -> Settings.load(Optional.of(dir.resolve("missing.json"))));
    }
}
