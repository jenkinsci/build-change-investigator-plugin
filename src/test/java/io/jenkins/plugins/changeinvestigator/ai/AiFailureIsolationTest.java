package io.jenkins.plugins.changeinvestigator.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.FreeStyleBuild;
import hudson.model.FreeStyleProject;
import hudson.model.Result;
import hudson.model.RootAction;
import hudson.util.FormValidation;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.ai.provider.AiProviderConfig;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.testutil.FakeChangeLogSCM;
import java.net.URL;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import org.htmlunit.HttpMethod;
import org.htmlunit.WebRequest;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestBuilder;
import org.jvnet.hudson.test.TestExtension;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.interceptor.RequirePOST;

/** Exercises the real analysis form and the connection boundary under injected provider failures. */
@WithJenkins
class AiFailureIsolationTest {
    private static final String PRIVATE_MARKER = "synthetic-provider-private-diagnostic-4917";

    public static final class FailingConfig extends AiProviderConfig {
        private final String mode;

        FailingConfig(String mode) {
            this.mode = mode;
        }

        @Override
        public String getModel() {
            return "test-model";
        }

        @Override
        public AiProvider createProvider(ObjectMapper mapper, int timeout) {
            if (mode.equals("creation")) throw new NoClassDefFoundError(PRIVATE_MARKER);
            return request -> {
                if (mode.equals("call")) throw new NoSuchMethodError(PRIVATE_MARKER);
                if (mode.equals("runtime")) throw new IllegalArgumentException(PRIVATE_MARKER);
                throw new AiAnalysisException(
                        AiAnalysisException.Kind.valueOf(mode),
                        PRIVATE_MARKER,
                        new IllegalArgumentException(PRIVATE_MARKER));
            };
        }
    }

    /** HTTP test adapter invokes the production Test Connection boundary without a live provider. */
    @TestExtension
    public static final class ConnectionEndpoint implements RootAction {
        @Override
        public String getIconFileName() {
            return null;
        }

        @Override
        public String getDisplayName() {
            return "Connection test";
        }

        @Override
        public String getUrlName() {
            return "failure-connection-test";
        }

        @RequirePOST
        public FormValidation doCheck() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            return ChangeInvestigatorGlobalConfiguration.get()
                    .getProviderConfig()
                    .testConnection(new ObjectMapper(), 2);
        }
    }

    @Test
    void providerFailuresStayLocalAndPrivateAcrossHttpAndPersistence(JenkinsRule j) throws Exception {
        FreeStyleProject project = j.createFreeStyleProject("failure-boundaries");
        project.setScm(new FakeChangeLogSCM(List.of(new FakeChangeLogSCM.FakeCommit(
                "a254c24", "Developer", "Update service", List.of("src/ServiceCheck.java")))));
        TestBuilder failure = new TestBuilder() {
            @Override
            public boolean perform(
                    hudson.model.AbstractBuild<?, ?> build,
                    hudson.Launcher launcher,
                    hudson.model.BuildListener listener) {
                listener.getLogger().println("[Pipeline] { (payments-integration-test)");
                listener.getLogger().println("java.lang.IllegalStateException: service handshake refused");
                listener.getLogger().println(" at com.acme.ServiceCheck.run(ServiceCheck.java:1)");
                return false;
            }
        };
        project.getBuildersList().add(failure);
        project.scheduleBuild2(0).get();
        project.getBuildersList().clear();
        project.scheduleBuild2(0).get();
        project.getBuildersList().add(failure);
        project.scheduleBuild2(0).get();
        FreeStyleBuild current = project.scheduleBuild2(0).get();
        InvestigationAction action = current.getAction(InvestigationAction.class);
        assertNotNull(action);
        assertFalse(action.hasAiAssessment());
        String originalCopy = action.getView().getCopyText();
        var config = ChangeInvestigatorGlobalConfiguration.get();
        config.setAiEnabled(true);
        var client = j.createWebClient();
        List<LogRecord> records = new ArrayList<>();
        Handler capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        Logger logger = Logger.getLogger("io.jenkins.plugins.changeinvestigator.ai");
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        capture.setLevel(Level.ALL);
        logger.addHandler(capture);
        List<String> modes = new ArrayList<>(List.of("creation", "call", "runtime"));
        for (AiAnalysisException.Kind kind : AiAnalysisException.Kind.values()) modes.add(kind.name());
        try {
            for (String mode : modes) {
                FailingConfig provider = new FailingConfig(mode);
                config.setProviderConfig(provider);
                FormValidation direct = provider.testConnection(new ObjectMapper(), 2);
                assertEquals(FormValidation.Kind.ERROR, direct.kind, mode);
                assertFalse(direct.getMessage().contains(PRIVATE_MARKER), mode);
                WebRequest request =
                        new WebRequest(new URL(j.getURL(), "failure-connection-test/check"), HttpMethod.POST);
                client.addCrumb(request);
                String connection = client.getPage(request).getWebResponse().getContentAsString();
                assertFalse(connection.contains(PRIVATE_MARKER), mode);
                assertFalse(connection.contains("Oops"), mode);
                HtmlPage before = client.getPage(current, "change-investigation/");
                HtmlPage after = j.submit(before.getFormByName("runAi"));
                assertEquals(200, after.getWebResponse().getStatusCode(), mode);
                String page = after.asNormalizedText();
                assertFalse(page.contains(PRIVATE_MARKER), mode);
                assertTrue(page.contains("ServiceCheck.java"), mode);
                assertTrue(page.contains("IllegalStateException"), mode);
                assertTrue(page.contains("FIRST BAD"), mode);
                assertTrue(page.contains("Seen before"), mode);
                assertTrue(page.contains("Compare builds"), mode);
                assertTrue(page.contains("Copy investigation"), mode);
                assertTrue(page.contains("payments-integration-test"), mode);
                assertNotNull(after.getElementById("jenkins-build-history"));
                assertNotNull(after.getFormByName("runAi"));
                assertTrue(action.getAiAssessment().isFailed(), mode);
                assertFalse(action.getAiAssessment().getErrorMessage().contains(PRIVATE_MARKER), mode);
                assertEquals(Result.FAILURE, current.getResult(), mode);
                assertEquals(originalCopy, action.getView().getCopyText(), mode);
                String xml = Files.readString(current.getRootDir().toPath().resolve("build.xml"));
                assertFalse(xml.contains(PRIVATE_MARKER), mode);
            }
            com.cloudbees.plugins.credentials.SystemCredentialsProvider.getInstance()
                    .getCredentials()
                    .add(new org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl(
                            com.cloudbees.plugins.credentials.CredentialsScope.GLOBAL,
                            "wire-test-credential",
                            "Synthetic test credential",
                            hudson.util.Secret.fromString(PRIVATE_MARKER)));
            record WireFailure(int status, String body, AiAnalysisException.Kind kind) {}
            String errorBody = "{\"error\":{\"message\":\"" + PRIVATE_MARKER + "\"}}";
            List<WireFailure> wireFailures = List.of(
                    new WireFailure(401, errorBody, AiAnalysisException.Kind.AUTHENTICATION_FAILED),
                    new WireFailure(403, errorBody, AiAnalysisException.Kind.AUTHORIZATION_FAILED),
                    new WireFailure(404, errorBody, AiAnalysisException.Kind.MODEL_NOT_FOUND),
                    new WireFailure(429, errorBody, AiAnalysisException.Kind.RATE_LIMITED),
                    new WireFailure(
                            429,
                            "{\"error\":{\"code\":\"insufficient_quota\",\"message\":\"" + PRIVATE_MARKER + "\"}}",
                            AiAnalysisException.Kind.QUOTA_EXCEEDED),
                    new WireFailure(503, errorBody, AiAnalysisException.Kind.PROVIDER_UNAVAILABLE),
                    new WireFailure(200, "{malformed:" + PRIVATE_MARKER, AiAnalysisException.Kind.MALFORMED_RESPONSE));
            for (WireFailure wire : wireFailures) {
                try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.startWithStatus(
                        wire.status(), wire.body())) {
                    config.setProviderConfig(
                            new io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig(
                                    server.baseUrl(), "test-model", "wire-test-credential"));
                    assertWireFailure(j, current, originalCopy, wire.kind());
                    assertNotNull(server.lastRequestBody);
                    assertEquals("Bearer " + PRIVATE_MARKER, server.lastAuthorizationHeader);
                }
            }
            config.setProviderConfig(
                    new io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig(
                            "http://bad host/?token=" + PRIVATE_MARKER, "test-model", "wire-test-credential"));
            assertWireFailure(j, current, originalCopy, AiAnalysisException.Kind.INVALID_ENDPOINT);
            String closedEndpoint;
            try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start("{}")) {
                closedEndpoint = server.baseUrl();
            }
            config.setProviderConfig(
                    new io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig(
                            closedEndpoint, "test-model", "wire-test-credential"));
            assertWireFailure(j, current, originalCopy, AiAnalysisException.Kind.CONNECTION_FAILED);
            try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(exchange -> {
                try {
                    Thread.sleep(1800);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "{}";
            })) {
                config.setTimeoutSeconds(1);
                config.setProviderConfig(
                        new io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig(
                                server.baseUrl(), "test-model", "wire-test-credential"));
                assertWireFailure(j, current, originalCopy, AiAnalysisException.Kind.TIMEOUT);
                assertNotNull(server.lastRequestBody);
            } finally {
                config.setTimeoutSeconds(30);
            }
            try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start("{}")) {
                config.setProviderConfig(
                        new io.jenkins.plugins.changeinvestigator.ai.provider.OpenAiCompatibleProviderConfig(
                                server.baseUrl(), " ", "wire-test-credential"));
                assertWireFailure(j, current, originalCopy, AiAnalysisException.Kind.CONFIGURATION_INVALID);
                assertNull(server.lastRequestBody);
            }
            try (var server = io.jenkins.plugins.changeinvestigator.testutil.MockAiServer.start(
                    "{\"promptFeedback\":{\"blockReason\":\"SAFETY\",\"blockReasonMessage\":\"" + PRIVATE_MARKER
                            + "\"}}")) {
                var gemini = new io.jenkins.plugins.changeinvestigator.ai.provider.GeminiProviderConfig(
                        "test-model", "wire-test-credential");
                gemini.setBaseUrl(server.baseUrl());
                config.setProviderConfig(gemini);
                assertWireFailure(j, current, originalCopy, AiAnalysisException.Kind.UNSUPPORTED_RESPONSE);
                assertNotNull(server.lastRequestBody);
            }
            // Reload through Jenkins' job/run loading lifecycle so RunAction2.onLoad binds the owner.
            // Run.reload() alone reads XML into the existing instance without that lifecycle callback.
            int currentNumber = current.getNumber();
            String projectName = project.getFullName();
            j.jenkins.reload();
            FreeStyleProject restoredProject = j.jenkins.getItemByFullName(projectName, FreeStyleProject.class);
            assertNotNull(restoredProject);
            FreeStyleBuild restoredBuild = restoredProject.getBuildByNumber(currentNumber);
            assertNotNull(restoredBuild);
            InvestigationAction restoredAction = restoredBuild.getAction(InvestigationAction.class);
            assertNotNull(restoredAction);
            assertTrue(restoredAction.getAiAssessment().isFailed());
            assertEquals(originalCopy, restoredAction.getView().getCopyText());
            for (LogRecord record : records) {
                assertFalse(record.getMessage().contains(PRIVATE_MARKER));
                assertNull(record.getThrown(), "Plugin logs must not attach raw provider exceptions");
                if (record.getParameters() != null) {
                    for (Object value : record.getParameters())
                        assertFalse(String.valueOf(value).contains(PRIVATE_MARKER));
                }
            }
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previous);
        }
    }

    private static void assertWireFailure(
            JenkinsRule j, FreeStyleBuild current, String originalCopy, AiAnalysisException.Kind expected)
            throws Exception {
        var client = j.createWebClient();
        HtmlPage before = client.getPage(current, "change-investigation/");
        HtmlPage after = j.submit(before.getFormByName("runAi"));
        String label = expected.name();
        assertEquals(200, after.getWebResponse().getStatusCode(), label);
        String page = after.asNormalizedText();
        assertFalse(page.contains(PRIVATE_MARKER), label);
        assertFalse(page.contains("Oops"), label);
        for (String required : List.of(
                "ServiceCheck.java",
                "IllegalStateException",
                "FIRST BAD",
                "Seen before",
                "Compare builds",
                "Copy investigation",
                "payments-integration-test")) {
            assertTrue(page.contains(required), label + ": " + required);
        }
        assertNotNull(after.getElementById("jenkins-build-history"));
        assertNotNull(after.getFormByName("runAi"));
        InvestigationAction action = current.getAction(InvestigationAction.class);
        assertTrue(action.getAiAssessment().isFailed(), label);
        assertEquals(SafeAiFailure.describe(expected), action.getAiAssessment().getErrorMessage(), label);
        assertEquals(Result.FAILURE, current.getResult(), label);
        assertEquals(originalCopy, action.getView().getCopyText(), label);
        assertFalse(
                Files.readString(current.getRootDir().toPath().resolve("build.xml"))
                        .contains(PRIVATE_MARKER),
                label);
    }
}
