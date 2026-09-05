package io.jenkins.plugins.changeinvestigator.investigation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ManifestEvidenceTest {
    @Test
    void observesModuleAndDependencyVersions() {
        String text = ManifestEvidence.describe(
                "<project><artifactId>payments</artifactId><version>1.0</version><dependencies><dependency><artifactId>payment-common</artifactId><version>2.4.0</version></dependency></dependencies></project>");
        assertTrue(text.contains("payment-common 2.4.0"));
        assertTrue(text.contains("payments 1.0"));
    }

    @Test
    void rejectsExternalEntities() {
        String text = ManifestEvidence.describe(
                "<!DOCTYPE project [<!ENTITY data SYSTEM 'file:///etc/passwd'>]><project><artifactId>&data;</artifactId></project>");
        assertEquals("Manifest could not be parsed safely.", text);
    }

    @Test
    void doesNotResolvePropertiesOrInventInheritedVersion() {
        assertTrue(ManifestEvidence.describe("<project><artifactId>payment-common</artifactId></project>")
                .contains("inherited or unavailable"));
        assertTrue(ManifestEvidence.describe(
                        "<project><artifactId>payment-common</artifactId><version>${revision}</version></project>")
                .contains("${revision}"));
    }

    @Test
    void storedObservationsCannotBeMutated() {
        var evidence = new ManifestEvidence(Map.of("pom.xml", "payments 1.0"));
        assertThrows(
                UnsupportedOperationException.class, () -> evidence.getValues().put("pom.xml", "changed"));
    }
}
