package io.jenkins.plugins.changeinvestigator.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BuildInvestigationEvidenceTest {

    @Test
    void logExcerptTextJoinsLinesWithRealNewlines() {
        BuildInvestigationEvidence evidence = BuildInvestigationEvidence.builder()
                .log(List.of("+ bash build.sh", "Running the demo build step...", "ERROR: boom"),
                        true, false, 3)
                .build();

        // Regression guard: the build page's index.jelly renders this value directly inside a
        // <pre> block. It must contain real newline characters between lines - previously the
        // view built this text itself with a <j:forEach> that emitted a newline as literal
        // Jelly-source whitespace, which the XML/Jelly parser silently collapsed, running every
        // log line together on the rendered page.
        assertEquals("+ bash build.sh\nRunning the demo build step...\nERROR: boom",
                evidence.getLogExcerptText());
    }

    @Test
    void logExcerptTextIsEmptyWhenNoLines() {
        BuildInvestigationEvidence evidence = BuildInvestigationEvidence.builder()
                .log(List.of(), false, false, 0)
                .build();

        assertEquals("", evidence.getLogExcerptText());
    }
}
