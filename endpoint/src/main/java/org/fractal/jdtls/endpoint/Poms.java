// SPDX-License-Identifier: Apache-2.0
package org.fractal.jdtls.endpoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/**
 * A POM read with the JDK's XML parser, for the endpoint's own list of a worktree's modules (design §11 "Worktrees",
 * T002.04's review R1). m2e's {@code LocalProjectScanner} leaves out a POM it can't read, and only logs why, so a
 * module whose POM is malformed would never reach the health check. This reads what's needed to find the modules
 * without m2e: the coordinates, the packaging, and {@code <modules>}, the project's own and each profile's. Nothing is
 * interpolated, and no external entity or DTD is loaded.
 */
final class Poms {

    private Poms() {}

    /** What the endpoint reads from a POM. */
    record Pom(String gav, String packaging, List<String> modules) {}

    /** A POM that can't be read as XML, or isn't a {@code <project>}: the reason. */
    static final class Unreadable extends Exception {
        private static final long serialVersionUID = 1L;

        Unreadable(String reason) {
            super(reason, null, false, false);
        }
    }

    static Pom read(Path pom) throws Unreadable {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(pom);
        } catch (IOException e) {
            throw new Unreadable("can't be read: " + e.getMessage());
        }
        Element project;
        try {
            DocumentBuilder builder = factory().newDocumentBuilder();
            builder.setErrorHandler(null);
            project = builder.parse(new java.io.ByteArrayInputStream(bytes), pom.toUri().toString()).getDocumentElement();
        } catch (SAXException e) {
            throw new Unreadable("isn't well-formed XML: " + e.getMessage());
        } catch (IOException | ParserConfigurationException e) {
            throw new Unreadable("can't be parsed: " + e.getMessage());
        }
        if (!"project".equals(project.getLocalName() == null ? project.getTagName() : project.getLocalName())) {
            throw new Unreadable("isn't a Maven POM: its root element is <" + project.getTagName() + ">, not <project>");
        }
        Element parent = child(project, "parent");
        String group = text(project, "groupId", parent);
        String version = text(project, "version", parent);
        String artifact = text(project, "artifactId", null);
        String packaging = text(project, "packaging", null);
        List<String> modules = new ArrayList<>(modules(project));
        Element profiles = child(project, "profiles");
        if (profiles != null) {
            for (Element profile : children(profiles, "profile")) {
                modules.addAll(modules(profile));
            }
        }
        return new Pom(group + ":" + artifact + ":" + version, packaging == null ? "jar" : packaging, modules);
    }

    private static DocumentBuilderFactory factory() throws ParserConfigurationException {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        f.setXIncludeAware(false);
        f.setExpandEntityReferences(false);
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        f.setFeature("http://xml.org/sax/features/external-general-entities", false);
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return f;
    }

    private static List<String> modules(Element owner) {
        List<String> result = new ArrayList<>();
        Element modules = child(owner, "modules");
        if (modules != null) {
            for (Element module : children(modules, "module")) {
                String value = module.getTextContent().trim();
                if (!value.isEmpty()) {
                    result.add(value);
                }
            }
        }
        return result;
    }

    private static String text(Element owner, String name, Element fallback) {
        Element e = child(owner, name);
        if (e == null && fallback != null) {
            e = child(fallback, name);
        }
        return e == null ? null : e.getTextContent().trim();
    }

    private static Element child(Element owner, String name) {
        List<Element> all = children(owner, name);
        return all.isEmpty() ? null : all.get(0);
    }

    private static List<Element> children(Element owner, String name) {
        List<Element> result = new ArrayList<>();
        for (Node n = owner.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName() == null ? e.getTagName() : e.getLocalName())) {
                result.add(e);
            }
        }
        return result;
    }
}
