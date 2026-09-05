package io.jenkins.plugins.changeinvestigator.investigation;

import hudson.FilePath;
import hudson.model.AbstractBuild;
import hudson.model.InvisibleAction;
import hudson.model.Run;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/** Small build-time manifest observations; no workspace or SCM access occurs while rendering a page. */
public final class ManifestEvidence extends InvisibleAction {
    private final Map<String, String> values;

    public ManifestEvidence(Map<String, String> values) {
        this.values = new LinkedHashMap<>(values);
    }

    public Map<String, String> getValues() {
        return java.util.Collections.unmodifiableMap(values);
    }

    public String value(String path) {
        return values.getOrDefault(path, "");
    }

    public static ManifestEvidence collect(Run<?, ?> run) throws IOException, InterruptedException {
        Map<String, String> values = new LinkedHashMap<>();
        if (run instanceof AbstractBuild<?, ?> build) {
            FilePath workspace = build.getWorkspace();
            if (workspace != null) {
                String root = read(workspace, "pom.xml");
                if (!root.isEmpty()) {
                    values.put("pom.xml", describe(root));
                    for (String module : modules(root)) {
                        if (values.size() >= 20) break;
                        if (!module.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+){0,3}") || module.contains(".."))
                            continue;
                        String path = module + "/pom.xml", content = read(workspace, path);
                        if (!content.isEmpty()) values.put(path, describe(content));
                    }
                }
            }
        }
        return new ManifestEvidence(values);
    }

    private static String read(FilePath workspace, String path) throws IOException, InterruptedException {
        FilePath cursor = workspace;
        for (String part : path.split("/")) {
            cursor = cursor.child(part);
            if (cursor.readLink() != null) return "";
        }
        if (!cursor.exists() || cursor.isDirectory()) return "";
        try (var input = cursor.read()) {
            byte[] bytes = input.readNBytes(16385);
            if (bytes.length > 16384) return "";
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static Element parse(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)))
                .getDocumentElement();
    }

    private static String child(Element element, String name) {
        for (Node n = element.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && e.getTagName().equals(name))
                return e.getTextContent().trim();
        return "";
    }

    public static String describe(String xml) {
        try {
            Element root = parse(xml);
            StringBuilder result = new StringBuilder();
            String artifact = child(root, "artifactId"), version = child(root, "version");
            if (!artifact.isBlank())
                result.append(artifact)
                        .append(" ")
                        .append(version.isBlank() ? "(version inherited or unavailable)" : version);
            var dependencies = root.getElementsByTagName("dependency");
            for (int i = 0; i < Math.min(20, dependencies.getLength()); i++) {
                Element dependency = (Element) dependencies.item(i);
                result.append("\n")
                        .append(child(dependency, "artifactId"))
                        .append(" ")
                        .append(child(dependency, "version"));
            }
            return FailureSignal.safe(result.toString(), 2000);
        } catch (Exception e) {
            return "Manifest could not be parsed safely.";
        }
    }

    private static java.util.List<String> modules(String xml) {
        try {
            var nodes = parse(xml).getElementsByTagName("module");
            java.util.List<String> result = new java.util.ArrayList<>();
            for (int i = 0; i < Math.min(19, nodes.getLength()); i++)
                result.add(nodes.item(i).getTextContent().trim());
            return result;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }
}
