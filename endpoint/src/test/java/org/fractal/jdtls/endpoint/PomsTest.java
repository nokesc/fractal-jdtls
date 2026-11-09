// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The endpoint's own reading of a POM's modules (T002.04's review, R1): no m2e, no interpolation, no external entity. */
class PomsTest {

    @TempDir
    Path tmp;

    Path pom(String text) throws Exception {
        return Files.writeString(tmp.resolve("pom.xml"), text);
    }

    @Test
    void theModulesOfTheProjectAndOfEachProfile() throws Exception {
        Poms.Pom p = Poms.read(pom("""
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.fractal</groupId><artifactId>fractal-harness</artifactId><version>0.1.0-SNAPSHOT</version>
                  <packaging>pom</packaging>
                  <modules><module>harness-contract</module><module> harness-app </module></modules>
                  <profiles><profile><id>cli</id><modules><module>harness-cli</module></modules></profile></profiles>
                </project>
                """));
        assertEquals("org.fractal:fractal-harness:0.1.0-SNAPSHOT", p.gav());
        assertEquals("pom", p.packaging());
        assertEquals(List.of("harness-contract", "harness-app", "harness-cli"), p.modules());
    }

    @Test
    void aChildInheritsItsParentsGroupAndVersionAndIsAJarByDefault() throws Exception {
        Poms.Pom p = Poms.read(pom("""
                <project><parent><groupId>org.fractal</groupId><artifactId>fractal-harness</artifactId>
                <version>0.1.0-SNAPSHOT</version></parent><artifactId>harness-app</artifactId></project>
                """));
        assertEquals("org.fractal:harness-app:0.1.0-SNAPSHOT", p.gav());
        assertEquals("jar", p.packaging());
        assertEquals(List.of(), p.modules());
    }

    @Test
    void aMalformedPomIsUnreadableWithTheParsersReason() throws Exception {
        Poms.Unreadable e = assertThrows(Poms.Unreadable.class, () -> Poms.read(pom("<project><artifactId>x</artifactId>")));
        assertTrue(e.getMessage().startsWith("isn't well-formed XML: "), e.getMessage());
        e = assertThrows(Poms.Unreadable.class, () -> Poms.read(pom("<settings/>")));
        assertEquals("isn't a Maven POM: its root element is <settings>, not <project>", e.getMessage());
        e = assertThrows(Poms.Unreadable.class, () -> Poms.read(tmp.resolve("missing.xml")));
        assertTrue(e.getMessage().startsWith("can't be read: "), e.getMessage());
    }

    @Test
    void noExternalEntityIsRead() throws Exception {
        Path secret = Files.writeString(tmp.resolve("secret.txt"), "the secret");
        String text = "<?xml version=\"1.0\"?>\n<!DOCTYPE project [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]>\n"
                + "<project><groupId>g</groupId><artifactId>a&x;</artifactId><version>1</version></project>";
        try {
            Poms.Pom p = Poms.read(pom(text));
            assertFalse(p.gav().contains("the secret"), p.gav());
        } catch (Poms.Unreadable refused) {
            // refusing the entity is as good
        }
    }
}
