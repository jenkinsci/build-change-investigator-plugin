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

    private static final Logger LOGGER = Logger.getLogger(InvestigationAction.class.getName());

    private transient Run<?, ?> run;

    private final BuildInvestigationEvidence evidence;
    private volatile AiAssessment aiAssessment;

    public InvestigationAction(Run<?, ?> run, BuildInvestigationEvidence evidence) {
        this.run = run;
        this.evidence = evidence;
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
        return evidence;
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
        } else {
            AiAnalysisService service = new AiAnalysisService(new ObjectMapper());
            this.aiAssessment = service.analyze(evidence, config.toProviderConfig(), config.resolveApiToken());
        }

        try {
            run.save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to persist AI assessment for " + run, e);
        }

        rsp.sendRedirect2(".");
    }
}
