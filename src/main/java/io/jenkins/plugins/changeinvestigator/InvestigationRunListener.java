package io.jenkins.plugins.changeinvestigator;

import hudson.Extension;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration;
import io.jenkins.plugins.changeinvestigator.evidence.BuildInvestigationEvidence;
import io.jenkins.plugins.changeinvestigator.evidence.EvidenceCollector;
import io.jenkins.plugins.changeinvestigator.security.ChangeInvestigatorPermissions;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Attaches a {@link InvestigationAction} to builds that finished worse than {@code SUCCESS},
 * collecting only the deterministic (non-AI) evidence at that point. This runs for every
 * build, but performs no network calls and no AI provider requests - it is pure local
 * Jenkins API usage, so it never causes "accidental" AI spend. AI analysis itself only ever
 * runs when a user explicitly clicks "Run AI Analysis" on the resulting page.
 */
@Extension
public class InvestigationRunListener extends RunListener<Run<?, ?>> {

    private static final Logger LOGGER = Logger.getLogger(InvestigationRunListener.class.getName());

    static {
        // Force ChangeInvestigatorPermissions to class-load (and self-register its Permission
        // with the Item permission group) as part of plugin startup, since @Extension classes
        // are guaranteed to be loaded eagerly by Jenkins but plain utility classes are not.
        // Without this, the permission would only appear in the matrix after something else
        // happened to reference it, which could be well after an administrator first opens the
        // permission matrix configuration page.
        LOGGER.log(Level.CONFIG, "Registered permission: {0}", ChangeInvestigatorPermissions.RUN_AI_ANALYSIS);
    }

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        Result result = run.getResult();
        if (result == null || result.isBetterOrEqualTo(Result.SUCCESS) || result.equals(Result.NOT_BUILT)) {
            return;
        }
        if (run.getAction(InvestigationAction.class) != null) {
            return;
        }

        try {
            int maxLogChars = ChangeInvestigatorGlobalConfiguration.get().getMaxLogContextChars();
            EvidenceCollector collector = new EvidenceCollector(maxLogChars);
            BuildInvestigationEvidence evidence = collector.collect(run);
            run.addAction(new InvestigationAction(run, evidence));
            run.save();
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to persist Build Change Investigator evidence for " + run, e);
        } catch (RuntimeException e) {
            // Evidence collection must never affect build recording; log and move on.
            LOGGER.log(Level.WARNING, "Failed to collect Build Change Investigator evidence for " + run, e);
        }
    }
}
