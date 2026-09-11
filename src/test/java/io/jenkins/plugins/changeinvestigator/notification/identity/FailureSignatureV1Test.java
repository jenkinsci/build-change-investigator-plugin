package io.jenkins.plugins.changeinvestigator.notification.identity;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FailureSignatureV1Test {
    private static final String JOB = "11111111-1111-1111-1111-111111111111";

    private static Map<String, String> compiler(String symbol) {
        return Map.of(
                "profile",
                "COMPILER",
                "scmSourceId",
                "source",
                "repositoryPath",
                "src/CollateralTrade.java",
                "diagnosticKind",
                "cannot.find.symbol",
                "discriminant",
                symbol);
    }

    private static FailureSignatureV1 signature(String symbol, int line) {
        return FailureSignatureV1.create(
                FailureSignatureV1.Category.COMPILER,
                compiler(symbol),
                new FailureSignatureV1.ContextFields(line, 24, "compile", "CollateralTrade.java:" + line),
                "observation-" + line);
    }

    private static ExecutionContextV1 context(ExecutionContextV1.Origin origin) {
        return ExecutionContextV1.create(
                JOB,
                List.of(new ExecutionContextV1.ScmSource("source", "repository", "BRANCH", "main", null)),
                origin,
                true,
                true);
    }

    @Test
    void lineDriftAndFramingDoNotSplitButSymbolDoes() {
        var first = signature("isPortolioIM", 853);
        assertTrue(first.sameIdentity(signature("isPortolioIM", 861)));
        assertFalse(first.sameIdentity(signature("anotherSymbol", 853)));
        assertTrue(first.sameIdentity(signature("\u001b[31misPortolioIM\u001b[0m", 853)));
        assertEquals(861, signature("isPortolioIM", 861).contextFields().line());
    }

    @Test
    void canonicalGoldenAndUnicodePreserveMeaningfulCaseAndNumbers() {
        var first = signature("isPortolioIM", 853);
        assertEquals(
                "{\"failureSignatureVersion\":1,\"normalizationProfile\":\"BCI_SIGNATURE_V1\",\"identityFields\":{\"profile\":\"COMPILER\",\"scmSourceId\":\"source\",\"repositoryPath\":\"src/CollateralTrade.java\",\"diagnosticKind\":\"cannot.find.symbol\",\"discriminant\":\"isPortolioIM\"}}",
                first.canonicalIdentity());
        assertTrue(signature("caf\u00e9", 1).sameIdentity(signature("cafe\u0301", 2)));
        assertFalse(signature("API2", 1).sameIdentity(signature("api2", 1)));
        assertFalse(signature("API2", 1).sameIdentity(signature("API3", 1)));
        assertNotEquals(
                IdentityCanonicalizer.canonical(Map.of("a", "x|b=y")),
                IdentityCanonicalizer.canonical(Map.of("a", "x", "b", "y")));
        assertEquals("{\"a\":null}", IdentityCanonicalizer.canonical(java.util.Collections.singletonMap("a", null)));
    }

    @Test
    void ambiguousBasenameAndTraversalFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SourcePathResolver.resolve(
                        "Service.java", null, List.of("a/Service.java", "b/Service.java"), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourcePathResolver.resolve("Service.java", null, List.of("a/Service.java"), false));
        assertThrows(IllegalArgumentException.class, () -> SourcePathResolver.relative("a/../Service.java"));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourcePathResolver.resolve(
                        "/unknown/src/Service.java", "/trusted", List.of("src/Service.java"), true));
        assertEquals(
                "src/Service.java",
                SourcePathResolver.resolve(
                        "C:\\trusted\\src\\Service.java", "C:\\trusted", List.of("src/Service.java"), true));
    }

    @Test
    void distinctRawPathsCannotCollapseIntoOneResolvedSource() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SourcePathResolver.resolve(
                        "src/File.java", null, List.of("src/File.java", "src\\File.java"), true));
        assertThrows(
                IllegalArgumentException.class,
                () -> SourcePathResolver.resolve(
                        "src/caf\u00e9.java", null, List.of("src/caf\u00e9.java", "src/cafe\u0301.java"), true));
        assertEquals(
                "src/File.java",
                SourcePathResolver.resolve("File.java", null, List.of("src/File.java", "src/File.java"), true));
    }

    @Test
    void criticalRedactionClippingAndGenericAreUnresolved() {
        for (String symbol : List.of("[REDACTED]", "password=synthetic-secret", "x".repeat(513))) {
            var value = signature(symbol, 1);
            assertEquals(FailureSignatureV1.Quality.UNRESOLVED, value.quality());
            assertNull(value.digest());
            assertNull(value.identityFields());
            assertFalse(value.sameIdentity(value));
        }
        assertEquals(
                FailureSignatureV1.Quality.UNRESOLVED,
                FailureSignatureV1.create(
                                FailureSignatureV1.Category.GENERIC,
                                Map.of(),
                                FailureSignatureV1.ContextFields.empty(),
                                "o")
                        .quality());
        assertEquals(
                FailureSignatureV1.Quality.UNRESOLVED,
                FailureSignatureV1.create(
                                FailureSignatureV1.Category.EXCEPTION,
                                Map.of("exceptionType", "IllegalStateException"),
                                FailureSignatureV1.ContextFields.empty(),
                                "o")
                        .quality());
    }

    @Test
    void apiAndTestsRequireDiscriminantsAndModule() {
        var api = Map.of(
                "profile",
                "API_LINKAGE",
                "exceptionType",
                "java.lang.NoSuchMethodError",
                "apiIdentity",
                "demo.Widget.fetch()Ljava/lang/String;",
                "moduleIdentity",
                "payments");
        var first = FailureSignatureV1.create(
                FailureSignatureV1.Category.API_LINKAGE, api, FailureSignatureV1.ContextFields.empty(), "o1");
        assertEquals(FailureSignatureV1.Quality.SPECIFIC, first.quality());
        var changed = new TreeMap<>(api);
        changed.put("moduleIdentity", "orders");
        assertFalse(first.sameIdentity(FailureSignatureV1.create(
                FailureSignatureV1.Category.API_LINKAGE, changed, FailureSignatureV1.ContextFields.empty(), "o2")));
        changed.put("apiIdentity", "unknown");
        assertEquals(
                FailureSignatureV1.Quality.UNRESOLVED,
                FailureSignatureV1.create(
                                FailureSignatureV1.Category.API_LINKAGE,
                                changed,
                                FailureSignatureV1.ContextFields.empty(),
                                "opaque-api")
                        .quality());
        changed.remove("apiIdentity");
        assertEquals(
                FailureSignatureV1.Quality.UNRESOLVED,
                FailureSignatureV1.create(
                                FailureSignatureV1.Category.API_LINKAGE,
                                changed,
                                FailureSignatureV1.ContextFields.empty(),
                                "o3")
                        .quality());
        var test = Map.of(
                "profile",
                "TEST",
                "testIdentity",
                "demo.WidgetTest.fetch[2]",
                "assertionKind",
                "java.lang.AssertionError",
                "assertionDiagnostic",
                "expected 2 but was 3",
                "moduleIdentity",
                "payments");
        assertEquals(
                FailureSignatureV1.Quality.SPECIFIC,
                FailureSignatureV1.create(
                                FailureSignatureV1.Category.TEST, test, FailureSignatureV1.ContextFields.empty(), "o4")
                        .quality());
        var runtime = Map.of(
                "profile",
                "EXCEPTION",
                "exceptionType",
                "java.lang.IllegalStateException",
                "applicationMethod",
                "demo.ServiceCheck.verify",
                "diagnosticDiscriminant",
                "required widget state unavailable",
                "moduleIdentity",
                "payments");
        assertTrue(FailureSignatureV1.create(
                        FailureSignatureV1.Category.EXCEPTION, runtime, FailureSignatureV1.ContextFields.empty(), "o5")
                .sameIdentity(FailureSignatureV1.create(
                        FailureSignatureV1.Category.EXCEPTION,
                        runtime,
                        new FailureSignatureV1.ContextFields(42, null, "changed stage", null),
                        "o6")));
    }

    @Test
    void contextSeparatesReplayHeadTargetAndSlotButNotSlotOrder() {
        assertNotEquals(
                context(ExecutionContextV1.Origin.NORMAL).digest(),
                context(ExecutionContextV1.Origin.REPLAY).digest());
        var pr = new ExecutionContextV1.ScmSource("source", "repository", "PULL_REQUEST", "main", "main");
        assertNotEquals(
                context(ExecutionContextV1.Origin.NORMAL).digest(),
                ExecutionContextV1.create(JOB, List.of(pr), ExecutionContextV1.Origin.NORMAL, true, true)
                        .digest());
        var pr2 = new ExecutionContextV1.ScmSource("source", "repository", "PULL_REQUEST", "main", "release");
        assertNotEquals(
                ExecutionContextV1.create(JOB, List.of(pr), ExecutionContextV1.Origin.NORMAL, true, true)
                        .digest(),
                ExecutionContextV1.create(JOB, List.of(pr2), ExecutionContextV1.Origin.NORMAL, true, true)
                        .digest());
        var other = new ExecutionContextV1.ScmSource("other", "repository2", "BRANCH", "main", null);
        assertEquals(
                ExecutionContextV1.create(JOB, List.of(pr, other), ExecutionContextV1.Origin.NORMAL, true, true)
                        .digest(),
                ExecutionContextV1.create(JOB, List.of(other, pr), ExecutionContextV1.Origin.NORMAL, true, true)
                        .digest());
        assertThrows(
                IllegalArgumentException.class,
                () -> new ExecutionContextV1.ScmSource(
                        "source", "https://user:secret@example.invalid/repo", "BRANCH", "main", null));
    }

    @Test
    void abbreviatedUuidCannotAliasCanonicalIdentity() {
        assertThrows(IllegalArgumentException.class, () -> ExecutionContextV1.unknown("1-1-1-1-1"));
        assertThrows(
                IllegalArgumentException.class,
                () -> InvestigationKeyV1.create(
                        JOB, context(ExecutionContextV1.Origin.NORMAL).digest(), signature("field", 1), "1-1-1-1-1"));
    }

    @Test
    void unknownAnchorIsUniqueAndSpecificAnchorImmutable() {
        var sig = signature("[REDACTED]", 1);
        var one = InvestigationKeyV1.create(
                JOB,
                context(ExecutionContextV1.Origin.NORMAL).digest(),
                sig,
                UUID.randomUUID().toString());
        var two = InvestigationKeyV1.create(
                JOB,
                context(ExecutionContextV1.Origin.NORMAL).digest(),
                sig,
                UUID.randomUUID().toString());
        assertNotEquals(one.digest(), two.digest());
        assertEquals(one, InvestigationKeyV1.create(JOB, one.contextDigest(), sig, one.episodeAnchorRunId()));
    }

    @Test
    void classifierRejectsGapsAncestryDuplicatesAndSeparatesRecurrence() {
        var ctx = context(ExecutionContextV1.Origin.NORMAL);
        var sig = signature("field", 1);
        var old = new CorrelationClassifier.PriorCase("case", JOB, ctx, sig, false);
        var complete = new CorrelationClassifier.Continuity(true, false, false);
        assertEquals(
                CorrelationClassifier.Status.SAME,
                CorrelationClassifier.classify(JOB, ctx, sig, List.of(old), complete)
                        .status());
        assertEquals(
                CorrelationClassifier.Status.UNKNOWN,
                CorrelationClassifier.classify(
                                JOB, ctx, sig, List.of(old), new CorrelationClassifier.Continuity(false, false, false))
                        .status());
        assertEquals(
                CorrelationClassifier.Status.UNKNOWN,
                CorrelationClassifier.classify(JOB, ctx, sig, List.of(old, old), complete)
                        .status());
        var uncertain = ExecutionContextV1.create(JOB, ctx.sources(), ctx.origin(), true, false);
        assertEquals(
                CorrelationClassifier.Status.UNKNOWN,
                CorrelationClassifier.classify(JOB, uncertain, sig, List.of(old), complete)
                        .status());
        var ended = new CorrelationClassifier.PriorCase("case", JOB, ctx, sig, true);
        var result = CorrelationClassifier.classify(JOB, ctx, sig, List.of(ended), complete);
        assertEquals(CorrelationClassifier.Status.NEW, result.status());
        assertEquals(List.of("case"), result.historicalCaseIds());
        assertEquals(
                CorrelationClassifier.Status.SIMILAR_HISTORICAL, CorrelationClassifier.historicalRelation(sig, sig));
    }

    @Test
    void adapterRequiresTrustedCompleteInventoryAndSourceSlot() {
        var ctx = context(ExecutionContextV1.Origin.NORMAL);
        var snapshot = new TrustedIdentityEvidence.Snapshot(
                ctx,
                FailureSignatureV1.Category.COMPILER,
                compiler("field"),
                FailureSignatureV1.ContextFields.empty(),
                "CollateralTrade.java",
                null,
                List.of("src/CollateralTrade.java"),
                true);
        assertEquals(
                FailureSignatureV1.Quality.SPECIFIC,
                NotificationSignatureAdapter.adapt(snapshot, "o").quality());
        assertEquals(
                FailureSignatureV1.Quality.UNRESOLVED,
                NotificationSignatureAdapter.adapt(null, "o").quality());
        var ambiguous = new TrustedIdentityEvidence.Snapshot(
                ctx,
                snapshot.category(),
                snapshot.identityFields(),
                snapshot.contextFields(),
                snapshot.reportedSourcePath(),
                null,
                List.of("one/CollateralTrade.java", "two/CollateralTrade.java"),
                true);
        assertEquals(
                FailureSignatureV1.Quality.UNRESOLVED,
                NotificationSignatureAdapter.adapt(ambiguous, "o").quality());
    }

    @Test
    void versionAndDigestCannotBeForgedOnReload() throws Exception {
        var value = signature("field", 1);
        ObjectMapper mapper = new ObjectMapper();
        var json = mapper.writeValueAsString(value);
        assertEquals(value, mapper.readValue(json, FailureSignatureV1.class));
        assertThrows(
                Exception.class,
                () -> mapper.readValue(
                        json.replace("\"failureSignatureVersion\":1", "\"failureSignatureVersion\":2"),
                        FailureSignatureV1.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> new FailureSignatureV1(
                        1,
                        value.extractorVersion(),
                        value.normalizationProfile(),
                        "o",
                        value.quality(),
                        value.category(),
                        value.identityFields(),
                        value.contextFields(),
                        List.of(),
                        "0".repeat(64)));
    }
}
