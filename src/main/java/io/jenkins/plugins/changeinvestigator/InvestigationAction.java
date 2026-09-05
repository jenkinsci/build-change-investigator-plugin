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
 * populated when a permitted user explicitly triggers {@link #doRunAi(StaplerResponse2)}, so simply viewing
 * a build page never causes an outbound AI request. The result is cached on the action (and
 * persisted with the build) so revisiting the page does not re-trigger analysis.
 */
public class InvestigationAction implements RunAction2 {

    /** Validate comparison parameters before Jelly rendering so invalid requests return HTTP 400. */
    public void doIndex(org.kohsuke.stapler.StaplerRequest2 request, StaplerResponse2 response)
            throws IOException, jakarta.servlet.ServletException {
        getView();
        request.getView(this, "index.jelly").forward(request, response);
    }

    private static final Logger LOGGER = Logger.getLogger(InvestigationAction.class.getName());

    private transient Run<?, ?> run;

    private final BuildInvestigationEvidence evidence;
    private volatile AiAssessment aiAssessment;
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
        if (aiAssessment == null || !aiAssessment.isCompleted()) return java.util.List.of();
        String cited = String.join("\n", aiAssessment.getSupportingEvidence());
        return java.util.List.of("E1", "E2", "E3").stream()
                .filter(id -> java.util.regex.Pattern.compile("\\b" + id + "\\b")
                        .matcher(cited)
                        .find())
                .toList();
    }

    public AiAssessment getAiAssessment() {
        return aiAssessment;
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

    /**
     * Runs (or re-runs) the AI assessment for this investigation and persists the result on
     * the build. Idempotent to call repeatedly - each call performs exactly one AI request,
     * so re-runs are always an explicit, visible user action rather than something that can
     * happen accidentally.
     */
    @POST
    public void doRunAi(StaplerResponse2 rsp) throws IOException {
        if (run == null) {
            throw new IllegalStateException("Action is not attached to a build.");
        }
        run.getParent().checkPermission(ChangeInvestigatorPermissions.RUN_AI_ANALYSIS);

        ChangeInvestigatorGlobalConfiguration config = ChangeInvestigatorGlobalConfiguration.get();
        if (!config.isAiEnabled()) {
            this.aiAssessment = AiAssessment.disabled();
        } else if (config.getProviderConfig() == null) {
            this.aiAssessment = AiAssessment.failed(
                    "AI analysis is enabled but no AI provider is configured. Go to Manage Jenkins -> System "
                            + "-> Build Change Investigator and select a provider.");
        } else {
            AiAnalysisService service = new AiAnalysisService(new ObjectMapper());
            this.aiAssessment = service.analyze(
                    evidence, config.getProviderConfig(), config.getTimeoutSeconds(), config.getTemperature());
        }

        try {
            run.save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to persist AI assessment for " + run, e);
        }

        rsp.sendRedirect2(".");
    }
}
