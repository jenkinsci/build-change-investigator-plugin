package io.jenkins.plugins.changeinvestigator.slack.config;

import hudson.Extension;
import hudson.init.Terminator;
import hudson.security.ACL;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;

/** Bounded administrator-requested refresh of stored exact member mappings. */
@Extension
public final class MappingValidationWorker {
    private volatile boolean stopping;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),
            runnable -> {
                Thread thread = new Thread(runnable, "BCI Slack mapping validation");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardOldestPolicy());

    static MappingValidationWorker get() {
        return Jenkins.get().getExtensionList(MappingValidationWorker.class).get(0);
    }

    void schedule(SlackConfiguration config, String credential, String fingerprint, String workspace) {
        if (stopping) return;
        var candidates = config.getResponderMappings();
        worker.execute(() -> {
            try (var ignored = ACL.as2(ACL.SYSTEM2)) {
                for (var mapping : candidates) {
                    if (stopping
                            || Thread.currentThread().isInterrupted()
                            || !config.validationContextMatches(credential, fingerprint, workspace)) return;
                    if (!SlackConfiguration.eligibleForAutomaticValidation(mapping, credential, fingerprint, workspace))
                        continue;
                    try {
                        var result = config.validateMappingResult(mapping.getId(), true);
                        if (result
                                == io.jenkins.plugins.changeinvestigator.slack.transport.MemberVerification.Status
                                        .NEEDS_VALIDATION) return;
                    } catch (IllegalArgumentException removedOrDuplicate) {
                        // Another administrator may have edited this row while validation was queued.
                    }
                }
            } catch (RuntimeException | LinkageError unavailable) {
                // Optional metadata must never affect Jenkins configuration or build execution.
            }
        });
    }

    boolean isStopping() {
        return stopping;
    }

    @Terminator
    public void stop() {
        stopping = true;
        worker.shutdownNow();
        try {
            worker.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
