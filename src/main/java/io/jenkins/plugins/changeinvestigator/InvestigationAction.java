package io.jenkins.plugins.changeinvestigator;

import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.model.Run;
import io.jenkins.plugins.changeinvestigator.ai.AiAnalysisService;
import io.jenkins.plugins.changeinvestigator.ai.AiAssessment;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.security.ChangeInvestigatorPermissions;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.StaplerResponse2;
import org.kohsuke.stapler.verb.POST;

/**
 * The "Build Change Investigation" action attached to failed/unstable/aborted builds.
 *
 * <p>Deterministic {@link #evidence} is collected once, at build completion, and never
 * requires an AI call. {@link #aiAssessment} starts {@code null} ("not yet run") and is only
 * populated by an explicit manual request or an opted-in Slack investigation. Simply viewing
 * a build page never causes an outbound AI request. The result is cached on the action (and
 * persisted with the build) so revisiting the page does not re-trigger analysis.
 */
public class InvestigationAction implements RunAction2, org.kohsuke.stapler.StaplerProxy {

    /** Validate page requests before Jelly rendering, without exposing a state-changing web method. */
    @Override
    public Object getTarget() {
        run.getParent().checkPermission(hudson.model.Item.READ);
        var request = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        if (request != null) {
            String path = request.getRestOfPath();
            if (path.isEmpty() || path.equals("/") || path.equals("/index") || path.equals("/index.jelly")) {
                getView();
            }
        }
        return this;
    }

    private static final Logger LOGGER = Logger.getLogger(InvestigationAction.class.getName());

    private transient Run<?, ?> run;

    private final BuildInvestigationEvidence evidence;
    private volatile AiAssessment aiAssessment;
    private String aiScope;
    private java.util.Set<String> attemptedAiScopes;
    private io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase investigation;
    private transient java.util.Map<String, io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase>
            comparisons;

    public io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase getView() {
        run.getParent().checkPermission(hudson.model.Item.READ);
        org.kohsuke.stapler.StaplerRequest2 request = org.kohsuke.stapler.Stapler.getCurrentRequest2();
        String baseline = request == null ? null : request.getParameter("baseline");
        String target = request == null ? null : request.getParameter("target");
        if (baseline == null && target == null) {
            if (investigation == null)
                investigation = new io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase(
                        evidence,
                        new io.jenkins.plugins.changeinvestigator.investigation.HistoryEvidence(
                                0, 0, "Historical evidence: first bad has not been verified."),
                        evidence.getPreviousSuccessfulBuildNumber(),
                        false,
                        null);
            return investigation;
        }
        int from, to;
        try {
            from = Integer.parseInt(baseline);
            to = Integer.parseInt(target);
        } catch (NumberFormatException e) {
            throw org.kohsuke.stapler.HttpResponses.error(400, "Choose valid build numbers.");
        }
        if (from < 1 || to <= from || to - from > 100)
            throw org.kohsuke.stapler.HttpResponses.error(
                    400, "Baseline must precede target within 100 build numbers.");
        Run<?, ?> start = run.getParent().getBuildByNumber(from),
                end = run.getParent().getBuildByNumber(to);
        if (start == null || end == null || start.isBuilding() || end.isBuilding())
            throw org.kohsuke.stapler.HttpResponses.error(400, "Both builds must be retained and completed.");
        synchronized (this) {
            if (comparisons == null) comparisons = new java.util.LinkedHashMap<>();
            String key = from + ":" + to;
            if (!comparisons.containsKey(key)) {
                if (comparisons.size() >= 8)
                    comparisons.remove(comparisons.keySet().iterator().next());
                var collector = new io.jenkins.plugins.changeinvestigator.evidence.EvidenceCollector(12000);
                var baseAction = start.getAction(InvestigationAction.class);
                var baseSignal = baseAction == null
                        ? null
                        : io.jenkins.plugins.changeinvestigator.investigation.FailureSignal.extract(
                                baseAction.getEvidence().getLogExcerpt());
                comparisons.put(
                        key,
                        new io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase(
                                collector.collect(end, start),
                                new io.jenkins.plugins.changeinvestigator.investigation.HistoryEvidence(
                                        0, 0, "Explicit build comparison; no first-bad assertion."),
                                from,
                                true,
                                baseSignal));
            }
            attachManifests(comparisons.get(key));
            return comparisons.get(key);
        }
    }

    public java.util.List<Run<?, ?>> getComparisonBuilds() {
        run.getParent().checkPermission(hudson.model.Item.READ);
        java.util.List<Run<?, ?>> builds = new java.util.ArrayList<>();
        Run<?, ?> cursor = run.getParent().getLastBuild();
        for (int i = 0; i < 100 && cursor != null; i++, cursor = cursor.getPreviousBuild())
            if (!cursor.isBuilding()) builds.add(cursor);
        return builds;
    }

    public InvestigationAction(Run<?, ?> run, BuildInvestigationEvidence evidence) {
        this.run = run;
        this.evidence = evidence;
        var signal =
                io.jenkins.plugins.changeinvestigator.investigation.FailureSignal.extract(evidence.getLogExcerpt());
        investigation = new io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase(
                evidence,
                io.jenkins.plugins.changeinvestigator.investigation.HistoryEvidence.collect(run, signal),
                evidence.getPreviousSuccessfulBuildNumber(),
                false,
                null);
        attachManifests(investigation);
    }

    private void attachManifests(io.jenkins.plugins.changeinvestigator.investigation.InvestigationCase value) {
        Run<?, ?> baseline = run.getParent().getBuildByNumber(value.getBaseline()),
                target = run.getParent().getBuildByNumber(value.getWindowEnd());
        value.attachManifests(
                baseline == null
                        ? null
                        : baseline.getAction(
                                io.jenkins.plugins.changeinvestigator.investigation.ManifestEvidence.class),
                target == null
                        ? null
                        : target.getAction(io.jenkins.plugins.changeinvestigator.investigation.ManifestEvidence.class));
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    @Override
    public String getIconFileName() {
        return "symbol-investigator plugin-build-change-investigator";
    }

    @Override
    public String getDisplayName() {
        return "Build Change Investigation";
    }

    @Override
    public String getUrlName() {
        return "change-investigation";
    }

    public BuildInvestigationEvidence getEvidence() {
        if (run != null) run.getParent().checkPermission(hudson.model.Item.READ);
        return evidence;
    }

    public java.util.List<String> getAiReferences() {
        return getAiPresentation().getReferences();
    }

    /** One immutable rendering snapshot; no instance is stored with the build. */
    public static final class AiPresentation {
        private final AiAssessment assessment;
        private final String scope;
        private final java.util.List<String> references;

        private AiPresentation(AiAssessment assessment, String scope) {
            this.assessment = assessment;
            this.scope = scope;
            String cited = assessment == null || !assessment.isCompleted()
                    ? ""
                    : String.join("\n", assessment.getSupportingEvidence());
            references = java.util.List.of("E1", "E2", "E3").stream()
                    .filter(id -> java.util.regex.Pattern.compile("\\b" + id + "\\b")
                            .matcher(cited)
                            .find())
                    .toList();
        }

        public AiAssessment getAssessment() {
            return assessment;
        }

        public String getScope() {
            return scope;
        }

        public java.util.List<String> getReferences() {
            return references;
        }

        public boolean hasAssessment() {
            return assessment != null;
        }
    }

    public synchronized AiPresentation getAiPresentation() {
        String scope =
                aiScope != null ? aiScope : (aiAssessment == null ? automaticAiEvidence() : evidence).getAiScope();
        return new AiPresentation(aiAssessment, scope);
    }

    private BuildInvestigationEvidence automaticAiEvidence() {
        if (investigation == null || !investigation.getHistory().isVerified()) return evidence;
        return evidence.forChangeWindow(investigation.getChanges(), investigation.getWindowEnd());
    }

    /** Historical assessments describe the original full corpus until explicitly rerun. */
    public String getAiScope() {
        return getAiPresentation().getScope();
    }

    public AiAssessment getAiAssessment() {
        return aiAssessment;
    }

    /** Whether an explicitly requested analysis is running; observing this never starts a request. */
    public synchronized boolean isAiAnalysisRunning() {
        return run != null
                && io.jenkins.plugins.changeinvestigator.ai.AiAnalysisExecutor.get()
                                .find(analysisKey(automaticAiEvidence().getAiScope()))
                        != null;
    }

    private String analysisKey(String scope) {
        return run.getExternalizableId() + "|" + run.getTimeInMillis() + "|" + scope;
    }

    public boolean hasAiAssessment() {
        return aiAssessment != null;
    }

    public boolean isAiEnabledGlobally() {
        return ChangeInvestigatorGlobalConfiguration.get().isAiEnabled();
    }

    public boolean canRunAi() {
        return run != null && run.getParent().hasPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS);
    }

    /** Starts or joins one analysis for an opted-in Slack investigation; never called by page rendering. */
    public boolean startSlackAiAnalysis() {
        if (run == null) return false;
        run.getParent().checkPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS);
        if (!io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty.eligible(run)
                || !io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration.get()
                        .isConfigured()) return false;
        requestAnalysis(true);
        return isAiAnalysisRunning();
    }

    private synchronized java.util.concurrent.CompletableFuture<AiAssessment> requestAnalysis(boolean automatic) {
        var config = ChangeInvestigatorGlobalConfiguration.get();
        if (!config.isAiEnabled() || config.getProviderConfig() == null) {
            AiAssessment result = !config.isAiEnabled()
                    ? AiAssessment.disabled()
                    : AiAssessment.failed(
                            "AI analysis is enabled but no AI provider is configured. Go to Manage Jenkins -> System "
                                    + "-> Build Change Investigator and select a provider.");
            if (!automatic) publishAnalysis(result, null);
            return java.util.concurrent.CompletableFuture.completedFuture(result);
        }
        BuildInvestigationEvidence input = automaticAiEvidence();
        String scope = input.getAiScope();
        var executor = io.jenkins.plugins.changeinvestigator.ai.AiAnalysisExecutor.get();
        String key = analysisKey(scope);
        var existing = executor.find(key);
        if (existing != null) return existing;
        if (automatic
                && aiAssessment != null
                && aiAssessment.isCompleted()
                && scope.equals(getAiPresentation().getScope()))
            return java.util.concurrent.CompletableFuture.completedFuture(aiAssessment);
        if (attemptedAiScopes == null) attemptedAiScopes = new java.util.HashSet<>();
        if (automatic && (attemptedAiScopes.contains(scope) || attemptedAiScopes.size() >= 16))
            return java.util.concurrent.CompletableFuture.completedFuture(aiAssessment);
        attemptedAiScopes.add(scope);
        // Record the attempt before outbound work. A restart must not silently repeat a model request.
        try {
            run.save();
        } catch (IOException | RuntimeException failure) {
            AiAssessment failed =
                    AiAssessment.failed("AI analysis could not be started safely. Try a manual analysis later.");
            if (!automatic) publishAnalysis(failed, scope);
            return java.util.concurrent.CompletableFuture.completedFuture(failed);
        }
        var reservation = executor.reserve(key);
        if (reservation == null) {
            AiAssessment failed = AiAssessment.failed("AI analysis is busy. Try a manual analysis later.");
            if (!executor.isStopping()) publishAnalysis(failed, scope);
            return java.util.concurrent.CompletableFuture.completedFuture(failed);
        }
        var future = reservation.future();
        if (!reservation.owner()) return future;
        var provider = config.getProviderConfig();
        int timeout = config.getTimeoutSeconds();
        double temperature = config.getTemperature();
        long slackRevision = io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration.get()
                .getRevision();
        var slackProperty =
                run.getParent().getProperty(io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty.class);
        long jobRevision = slackProperty == null ? -1 : slackProperty.getRevision();
        boolean submitted = executor.submit(() -> {
            AiAssessment result;
            try (var ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
                if (automatic
                        && (!io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty.eligible(run)
                                || !io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration.get()
                                        .isConfigured()
                                || !ChangeInvestigatorGlobalConfiguration.get().isAiEnabled()
                                || ChangeInvestigatorGlobalConfiguration.get().getProviderConfig() != provider
                                || io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration.get()
                                                .getRevision()
                                        != slackRevision
                                || run.getParent()
                                                .getProperty(
                                                        io.jenkins.plugins.changeinvestigator.slack.config
                                                                .SlackJobProperty.class)
                                        != slackProperty
                                || slackProperty.getRevision() != jobRevision)) {
                    result = AiAssessment.failed(
                            "Automatic analysis was cancelled because notification settings changed.");
                } else
                    result = new AiAnalysisService(new ObjectMapper()).analyze(input, provider, timeout, temperature);
            } catch (RuntimeException | LinkageError failure) {
                result = AiAssessment.failed("AI analysis is unavailable. Observed evidence remains available.");
            }
            try {
                if (!executor.isStopping()) publishCurrentAnalysis(result, scope);
            } finally {
                executor.complete(key, future, result);
            }
        });
        if (!submitted) {
            AiAssessment result = AiAssessment.failed("AI analysis is busy. Try a manual analysis later.");
            if (!executor.isStopping()) publishAnalysis(result, scope);
            executor.complete(key, future, result);
        }
        return future;
    }

    private void publishAnalysis(AiAssessment result, String scope) {
        aiScope = scope;
        aiAssessment = result;
        try {
            run.save();
        } catch (IOException | RuntimeException failure) {
            LOGGER.log(Level.WARNING, "Failed to persist AI assessment; assessment remains available in memory.");
        }
    }

    private void publishCurrentAnalysis(AiAssessment result, String scope) {
        try (var ignored = hudson.security.ACL.as2(hudson.security.ACL.SYSTEM2)) {
            var currentJob = jenkins.model.Jenkins.get()
                    .getItemByFullName(run.getParent().getFullName(), hudson.model.Job.class);
            var currentRun = currentJob == null ? null : currentJob.getBuildByNumber(run.getNumber());
            if (currentRun == null || currentRun.getTimeInMillis() != run.getTimeInMillis()) return;
            var current = currentRun.getAction(InvestigationAction.class);
            if (current == null) return;
            synchronized (current) {
                if (scope.equals(current.automaticAiEvidence().getAiScope())) current.publishAnalysis(result, scope);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            LOGGER.warning(
                    "AI assessment could not be attached to the current build; observed evidence remains available.");
        }
    }

    /** Explicit manual analysis may rerun a completed assessment, but joins existing work for the same scope. */
    @POST
    public void doRunAi(StaplerResponse2 rsp) throws IOException {
        if (run == null) throw new IllegalStateException("Action is not attached to a build.");
        run.getParent().checkPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS);
        var future = requestAnalysis(false);
        try {
            future.get(
                    ChangeInvestigatorGlobalConfiguration.get().getTimeoutSeconds() + 10L,
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            // Background work remains bounded; the page continues to show observed evidence.
        }
        rsp.sendRedirect2(".");
    }
}
