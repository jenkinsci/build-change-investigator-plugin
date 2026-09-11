package io.jenkins.plugins.changeinvestigator.notification.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jenkins.plugins.changeinvestigator.notification.NotificationObservation;
import io.jenkins.plugins.changeinvestigator.notification.identity.ExecutionContextV1;
import io.jenkins.plugins.changeinvestigator.notification.identity.FailureSignatureV1;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.CoverageEvidence;
import io.jenkins.plugins.changeinvestigator.notification.lifecycle.MaterialFacts;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EmailFixtures {
    static NotificationObservation input(UUID jobId, int order, String candidate, boolean recovered) throws Exception {
        String id = UUID.nameUUIDFromBytes((jobId + ":" + order).getBytes(StandardCharsets.UTF_8))
                .toString();
        var context = ExecutionContextV1.create(
                jobId.toString(),
                List.of(new ExecutionContextV1.ScmSource("primary", "demo-repository", "BRANCH", "main", null)),
                ExecutionContextV1.Origin.NORMAL,
                true,
                true);
        var signature = FailureSignatureV1.create(
                FailureSignatureV1.Category.COMPILER,
                Map.of(
                        "profile",
                        "COMPILER",
                        "scmSourceId",
                        "primary",
                        "repositoryPath",
                        "src/Trade.java",
                        "diagnosticKind",
                        "cannot-find-symbol",
                        "discriminant",
                        "missingSymbol"),
                FailureSignatureV1.ContextFields.empty(),
                id);
        var facts = new MaterialFacts(
                "",
                List.of(new MaterialFacts.Candidate(
                        "repo", "src/Trade.java", candidate, "STRONG", List.of("SAME_FILE"))),
                List.of(candidate),
                "",
                "",
                "");
        ObjectNode display;
        try (var stream = EmailFixtures.class.getResourceAsStream(
                "/io/jenkins/plugins/changeinvestigator/notification/events-v1.json")) {
            display = (ObjectNode) new ObjectMapper().readTree(stream).get("specific");
        }
        display.putObject("boundary")
                .putNull("lastKnownGood")
                .putNull("firstBad")
                .put("firstBadVerified", false)
                .put("proofStatus", "UNKNOWN");
        ((ObjectNode) display.get("build"))
                .put("runId", id)
                .put("number", order)
                .put("result", recovered ? "SUCCESS" : "FAILURE");
        return new NotificationObservation(
                id,
                id,
                order,
                recovered ? "SUCCESS" : "FAILURE",
                true,
                true,
                context,
                signature,
                "compile:demo",
                facts,
                recovered
                        ? new CoverageEvidence("compiler-task", 1, "compile:demo", id, context.digest(), true, true)
                        : CoverageEvidence.unknown(),
                List.of(),
                display);
    }
}
