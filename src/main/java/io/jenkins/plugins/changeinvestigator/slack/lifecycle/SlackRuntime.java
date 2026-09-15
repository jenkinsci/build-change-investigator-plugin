package io.jenkins.plugins.changeinvestigator.slack.lifecycle;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.AsyncPeriodicWork;
import hudson.model.FreeStyleProject;
import hudson.model.Job;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.InvestigationAction;
import io.jenkins.plugins.changeinvestigator.investigation.FailureSignal;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackConfiguration;
import io.jenkins.plugins.changeinvestigator.slack.config.SlackJobProperty;
import io.jenkins.plugins.changeinvestigator.slack.message.SlackMessageRenderer;
import io.jenkins.plugins.changeinvestigator.slack.message.SlackSnapshot;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackRoute;
import io.jenkins.plugins.changeinvestigator.slack.transport.SlackTransport;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/** Small bounded worker pool. Build listeners only enqueue; pages never contact Slack. */
@Extension
public final class SlackRuntime {
    private static final Logger LOG = Logger.getLogger(SlackRuntime.class.getName());
    private volatile boolean stopping;
    private final Set<Job<?, ?>> scheduled = Collections.newSetFromMap(new WeakHashMap<>());
    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256), task -> {
                Thread thread = new Thread(task, "BCI Slack delivery");
                thread.setDaemon(true);
                return thread;
            });

    public static SlackRuntime get() {
        return ExtensionList.lookupSingleton(SlackRuntime.class);
    }

    public void schedule(Job<?, ?> job, int completedBuild) {
        if (stopping) return;
        synchronized (scheduled) {
            if (!scheduled.add(job)) return;
        }
        try {
            workers.execute(() -> {
                try (var ignored = ACL.as2(ACL.SYSTEM2)) {
                    process(job, completedBuild);
                } catch (IOException | RuntimeException | LinkageError failure) {
                    LOG.warning("Slack notification work could not complete safely; the investigation is unaffected.");
                } finally {
                    synchronized (scheduled) {
                        scheduled.remove(job);
                    }
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            synchronized (scheduled) {
                scheduled.remove(job);
            }
        }
    }

    private void process(Job<?, ?> job, int hint) throws IOException {
        if (stopping) return;
        if (Jenkins.get().getItemByFullName(job.getFullName()) != job) return;
        var config = SlackConfiguration.get();
        var property = job.getProperty(SlackJobProperty.class);
        EpisodeStore store =
                new EpisodeStore(job, () -> stopping || Thread.currentThread().isInterrupted());
        EpisodeEngine.State state = store.read();
        // No other worker can hold this job: a retained lease belongs to a terminated or failed pass.
        if (EpisodeEngine.restarted(state)) store.save(state);
        int latest = job.getLastBuild() == null ? 0 : job.getLastBuild().getNumber();
        if (property == null || !property.isEnabled() || !config.isConfigured()) {
            cancel(state);
            state.lastBuild = latest;
            state.configurationDigest = null;
            state.safeStatus = "";
            state.optInAfter = -1;
            store.save(state);
            return;
        }
        String key = key(job);
        if (state.configurationDigest == null
                || !state.configurationDigest.equals(key)
                || state.optInAfter != property.getEnabledAfterBuild()) {
            cancel(state);
            state.active = null;
            state.history.clear();
            state.lastBuild = Math.max(state.lastBuild, property.getEnabledAfterBuild());
            long modified = Math.max(config.getModifiedAtMillis(), property.getModifiedAtMillis());
            // A completed build from the preceding settings must never move to a newly selected route.
            for (int number = state.lastBuild + 1; number <= latest; number++) {
                Run<?, ?> old = job.getBuildByNumber(number);
                if (old != null && old.getStartTimeInMillis() >= modified) break;
                state.lastBuild = number;
            }
            state.optInAfter = property.getEnabledAfterBuild();
            state.configurationDigest = key;
            state.nextPreflight = 0;
            store.save(state);
        }
        int processed = 0;
        for (int number = state.lastBuild + 1; number <= latest && processed++ < 50; number++) {
            Run<?, ?> run = job.getBuildByNumber(number);
            if (run == null) {
                state.lastBuild = number;
                continue;
            }
            // POST_PRODUCTION is not building, but completion listeners still collect evidence.
            if (run.isLogUpdated()) break;
            if (!SlackJobProperty.eligible(run)) {
                state.lastBuild = number;
                continue;
            }
            if (run.getStartTimeInMillis() < config.getEnabledAtMillis()) {
                state.lastBuild = number;
                continue;
            }
            if (!key.equals(key(job))) return;
            store.ensureActive();
            observe(run, state, config);
            store.save(state);
            if (state.lastBuild < number) break;
        }
        var episodes = new ArrayList<>(state.history);
        if (state.active != null) episodes.add(state.active);
        for (var episode : episodes) {
            if (!key.equals(key(job))) return;
            deliver(job, state, episode, store, config, key);
        }
    }

    private static void observe(Run<?, ?> run, EpisodeEngine.State state, SlackConfiguration config)
            throws IOException {
        long now = System.currentTimeMillis();
        if (Result.SUCCESS.equals(run.getResult())) {
            if (state.active == null || state.active.closed) {
                state.lastBuild = run.getNumber();
                return;
            }
            var proof = ComparableCheck.passed(run, state.active.check, executionContext(run));
            if (proof == null) {
                state.lastBuild = run.getNumber();
                state.safeStatus = "Recovery pending: the affected check has not been verified as passing.";
                return;
            }
            state.safeStatus = "";
            var previous = SlackSnapshot.fromJson(state.active.context);
            String payload = SlackMessageRenderer.recovery(
                    previous,
                    run.getNumber(),
                    proof.getExplanation(),
                    likelyRecoveryChange(run, state.active, previous),
                    SlackSnapshot.trustedBuildUrl(run));
            if (SlackTransport.get().isSafeToStore(config.getCredentialId(), payload))
                EpisodeEngine.success(state, run.getNumber(), proof, payload, now);
            return;
        }
        if (!Result.FAILURE.equals(run.getResult()) && !Result.UNSTABLE.equals(run.getResult())) {
            state.lastBuild = run.getNumber();
            return;
        }
        int baseline = state.active == null || state.active.closed
                ? 0
                : SlackSnapshot.fromJson(state.active.context).getLastGood();
        SlackSnapshot snapshot = SlackSnapshot.capture(run, baseline);
        if (snapshot == null || snapshot.getSignature().isEmpty()) {
            state.lastBuild = run.getNumber();
            return;
        }
        boolean initial =
                state.active == null || state.active.closed || !state.active.signature.equals(snapshot.getSignature());
        if (initial) {
            snapshot = SlackSnapshot.capture(run, 0);
            if (snapshot == null || snapshot.getSignature().isEmpty()) {
                state.lastBuild = run.getNumber();
                return;
            }
        }
        int knownFirstBad = initial ? snapshot.getFirstBad() : state.active.knownFirstBad;
        if (knownFirstBad == 0 && snapshot.getFirstBad() > 0) knownFirstBad = snapshot.getFirstBad();
        snapshot.setFirstBad(knownFirstBad);
        snapshot.setMaterial(EpisodeEngine.digest(snapshot.getMaterial() + "|" + knownFirstBad));
        var transport = SlackTransport.get();
        if (initial && now < state.nextPreflight) return;
        var connection = initial
                ? transport.checkConnection(
                        config.getCredentialId(), SlackJobProperty.effectiveChannel(run.getParent()))
                : null;
        if (initial && !connection.success()) {
            state.safeStatus = connection.safeMessage();
            long delay = Math.max(30, connection.retryAfterSeconds());
            long retryFrom = System.currentTimeMillis();
            state.nextPreflight =
                    delay > (Long.MAX_VALUE - retryFrom) / 1000 ? Long.MAX_VALUE : retryFrom + delay * 1000;
            return;
        }
        state.nextPreflight = 0;
        long mappingRevision = config.getMappingRevision();
        String mention = initial
                ? config.mappedSlackUser(
                        snapshot.getAuthor(), connection.route().teamId())
                : null;
        if (mention != null && !transport.verifyMappedUser(config.getCredentialId(), connection.route(), mention))
            mention = null;
        String payload =
                initial ? SlackMessageRenderer.initial(snapshot, mention) : SlackMessageRenderer.material(snapshot);
        if (!transport.isSafeToStore(config.getCredentialId(), payload)
                || !transport.isSafeToStore(config.getCredentialId(), snapshot.toJson())) {
            state.safeStatus = "The notification could not be stored safely.";
            return;
        }
        state.safeStatus = "";
        var action = run.getAction(InvestigationAction.class);
        var check = ComparableCheck.failed(
                run, FailureSignal.extract(action.getEvidence().getLogExcerpt()), executionContext(run));
        if (!transport.isSafeToStore(
                config.getCredentialId(), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(check)))
            check = null;
        String result = EpisodeEngine.failure(
                state,
                run.getNumber(),
                snapshot.getSignature(),
                snapshot.getMaterial(),
                check,
                initial ? connection.route().channelId() : state.active.channel,
                payload,
                now);
        if (result.equals("INITIAL")) {
            state.active.context = snapshot.toJson();
            state.active.deliveries.get(0).mappingRevision = mappingRevision;
            state.active.routeTeam = connection.route().teamId();
            state.active.credentialId = config.getCredentialId();
            state.active.configurationDigest = state.configurationDigest;
        }
        state.active.knownFirstBad = knownFirstBad;
    }

    private static void deliver(
            Job<?, ?> job,
            EpisodeEngine.State state,
            EpisodeEngine.Episode episode,
            EpisodeStore store,
            SlackConfiguration config,
            String key)
            throws IOException {
        if (!key.equals(episode.configurationDigest)) return;
        for (var delivery : episode.deliveries) {
            if (delivery.status.equals("QUEUED")
                    && delivery.kind.equals("INITIAL")
                    && delivery.attempts == 0
                    && !delivery.aiRequested) {
                delivery.aiRequested = true;
                var aiConfig = io.jenkins.plugins.changeinvestigator.config.ChangeInvestigatorGlobalConfiguration.get();
                Run<?, ?> run = job.getBuildByNumber(delivery.build);
                var action = run == null ? null : run.getAction(InvestigationAction.class);
                var original = SlackSnapshot.fromJson(episode.context);
                var current = run == null ? null : SlackSnapshot.capture(run, 0);
                if (aiConfig.isAiEnabled()
                        && aiConfig.getProviderConfig() != null
                        && action != null
                        && original.getAi().isBlank()
                        && matchingAiScope(original, current)) {
                    delivery.aiDeadline = System.currentTimeMillis() + 30000;
                    delivery.nextAttempt = System.currentTimeMillis() + 1000;
                    store.save(state);
                    if (!key.equals(key(job))) return;
                    action.startSlackAiAnalysis();
                }
                store.save(state);
            }
            if (delivery.status.equals("QUEUED")
                    && delivery.kind.equals("INITIAL")
                    && delivery.aiDeadline > 0
                    && delivery.attempts == 0) {
                Run<?, ?> run = job.getBuildByNumber(delivery.build);
                SlackSnapshot snapshot = run == null ? null : SlackSnapshot.capture(run, 0);
                var original = SlackSnapshot.fromJson(episode.context);
                if (matchingAiScope(original, snapshot)
                        && snapshot.isAiPending()
                        && System.currentTimeMillis() < delivery.aiDeadline) return;
                if (matchingAiScope(original, snapshot)
                        && !snapshot.getAi().isBlank()
                        && System.currentTimeMillis() <= delivery.aiDeadline) {
                    original.includeAi(snapshot);
                    // Keep the original verified mention and deterministic payload; add only the bounded
                    // interpretation.
                    String mention = delivery.mappingRevision == config.getMappingRevision()
                            ? config.mappedSlackUser(original.getAuthor(), episode.routeTeam)
                            : null;
                    if (mention != null
                            && !SlackTransport.get()
                                    .verifyMappedUser(
                                            episode.credentialId,
                                            new SlackRoute(episode.routeTeam, episode.channel),
                                            mention)) mention = null;
                    String payload = SlackMessageRenderer.initial(original, mention);
                    if (SlackTransport.get().isSafeToStore(episode.credentialId, payload)) delivery.payload = payload;
                }
                delivery.aiDeadline = 0;
                delivery.nextAttempt = System.currentTimeMillis();
            }
        }
        for (var queued : episode.deliveries) {
            if (queued.status.equals("QUEUED")
                    && queued.kind.equals("INITIAL")
                    && (queued.attempts > 0
                            || queued.mappingRevision != config.getMappingRevision()
                            || !config.isTagResponders())) {
                queued.payload = SlackMessageRenderer.withoutResponderMention(
                        queued.payload, SlackSnapshot.fromJson(episode.context).getAuthor());
            }
        }
        var delivery = EpisodeEngine.claim(episode, System.currentTimeMillis());
        if (delivery == null || !key.equals(key(job))) return;
        store.save(state);
        if (!key.equals(key(job))) {
            delivery.status = "FAILED";
            delivery.safeStatus = "Notification settings changed; not sent";
            store.save(state);
            return;
        }
        if (delivery.kind.equals("INITIAL")
                && (delivery.attempts > 1
                        || delivery.mappingRevision != config.getMappingRevision()
                        || !config.isTagResponders())) {
            delivery.payload = SlackMessageRenderer.withoutResponderMention(
                    delivery.payload, SlackSnapshot.fromJson(episode.context).getAuthor());
            store.save(state);
        }
        store.ensureActive();
        var result = SlackTransport.get()
                .post(
                        episode.credentialId,
                        new SlackRoute(episode.routeTeam, episode.channel),
                        delivery.payload,
                        delivery.kind.equals("INITIAL") ? null : episode.rootTs,
                        delivery.id);
        switch (result.outcome()) {
            case ACCEPTED -> EpisodeEngine.sent(episode, delivery, result.timestamp());
            case UNKNOWN -> EpisodeEngine.uncertain(delivery);
            case PERMANENT -> {
                delivery.status = "FAILED";
                delivery.safeStatus = result.safeMessage();
            }
            case RETRY -> {
                long seconds = Math.max(1, result.retryAfterSeconds());
                long now = System.currentTimeMillis();
                long due = seconds > (Long.MAX_VALUE - now) / 1000 ? Long.MAX_VALUE : now + seconds * 1000;
                EpisodeEngine.retry(delivery, due);
            }
        }
        store.save(state);
    }

    static boolean matchingAiScope(SlackSnapshot original, SlackSnapshot current) {
        return original != null
                && current != null
                && original.getAiScope() != null
                && !original.getAiScope().isBlank()
                && original.getAiScope().equals(current.getAiScope());
    }

    private static String key(Job<?, ?> job) {
        var config = SlackConfiguration.get();
        var property = job.getProperty(SlackJobProperty.class);
        return EpisodeEngine.digest(config.isConfigured() + "|" + config.getCredentialId() + "|"
                + SlackJobProperty.effectiveChannel(job) + "|" + (property != null && property.isEnabled()) + "|"
                + config.getRevision() + "|" + (property == null ? -1 : property.getRevision()) + "|"
                + (property == null ? -1 : property.getEnabledAtMillis()) + "|"
                + (property == null ? -1 : property.getEnabledAfterBuild()));
    }

    private static void cancel(EpisodeEngine.State state) {
        var episodes = new ArrayList<>(state.history);
        if (state.active != null) episodes.add(state.active);
        for (var episode : episodes)
            for (var delivery : episode.deliveries)
                if (delivery.status.equals("QUEUED")) {
                    delivery.status = "FAILED";
                    delivery.safeStatus = "Notification settings changed; not sent";
                }
    }

    /** The configured execution identity is frozen before the build, never reconstructed from a changed job. */
    public static final class ExecutionContext extends hudson.model.InvisibleAction implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        private final String configurationHash;

        ExecutionContext(String configurationHash) {
            this.configurationHash = configurationHash;
        }
    }

    static String executionContext(Run<?, ?> run) {
        if (!(run.getParent() instanceof FreeStyleProject)) return "";
        ExecutionContext context = run.getAction(ExecutionContext.class);
        if (context == null || context.configurationHash == null || !context.configurationHash.matches("[a-f0-9]{64}"))
            return "";
        String node = run instanceof hudson.model.AbstractBuild<?, ?> build ? build.getBuiltOnStr() : "";
        return EpisodeEngine.digest(context.configurationHash + "|" + node);
    }

    private static void captureExecutionContext(Run<?, ?> run) throws IOException {
        if (!(run.getParent() instanceof FreeStyleProject project) || run.getAction(ExecutionContext.class) != null)
            return;
        String configuration = Jenkins.XSTREAM2.toXML(project.getBuildersList().toList())
                + Jenkins.XSTREAM2.toXML(project.getPublishersList().toList())
                + Jenkins.XSTREAM2.toXML(project.getScm());
        if (configuration.length() > 128000) return;
        var context = new ExecutionContext(EpisodeEngine.digest(configuration));
        run.addAction(context);
        try {
            run.save();
        } catch (IOException | RuntimeException failure) {
            run.removeAction(context);
            throw failure;
        }
    }

    private static String likelyRecoveryChange(Run<?, ?> run, EpisodeEngine.Episode episode, SlackSnapshot previous) {
        try {
            Run<?, ?> failed = run.getParent().getBuildByNumber(episode.lastFailure);
            if (failed == null || previous.getSource().isBlank()) return "";
            var evidence =
                    new io.jenkins.plugins.changeinvestigator.evidence.EvidenceCollector(12000).collect(run, failed);
            var matches = evidence.getChangeEntries().stream()
                    .filter(entry -> entry.getAffectedFiles().stream()
                            .anyMatch(path -> path.replace('\\', '/').equals(previous.getSource())))
                    .toList();
            if (matches.size() != 1 || matches.get(0).getCommitId() == null) return "";
            return SlackSnapshot.safe(matches.get(0).getCommitId(), 80) + " changed " + previous.getSource()
                    + " after the last matching failure; this is a likely recovery change, not proof of the fix.";
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public static String safeStatus(Job<?, ?> job) {
        job.checkPermission(hudson.model.Item.READ);
        try {
            var state = new EpisodeStore(job).read();
            if (state.active != null)
                for (var delivery : state.active.deliveries) {
                    if (delivery.status.equals("UNKNOWN_OUTCOME"))
                        return "Delivery could not be confirmed. Automatic resend is paused to avoid duplicate messages.";
                    if (delivery.status.equals("FAILED"))
                        return "Slack delivery failed. Check the bot credential and channel permissions.";
                }
            if ("Recovery pending: the affected check has not been verified as passing.".equals(state.safeStatus))
                return "Recovery pending: the affected check has not been verified as passing.";
            if (state.safeStatus != null && !state.safeStatus.isBlank())
                return "Slack connection is unavailable. Check the bot credential and channel.";
            if (state.active == null || state.active.deliveries.isEmpty()) return "";
            return switch (state.active.deliveries.get(state.active.deliveries.size() - 1).status) {
                case "SENT" -> "Sent.";
                case "LEASED" -> "Slack delivery is in progress.";
                default -> "Waiting for Slack delivery.";
            };
        } catch (IOException | RuntimeException ignored) {
            return "Notification status is unavailable. The investigation remains available.";
        }
    }

    @hudson.init.Terminator
    public static void stop() {
        SlackRuntime runtime = get();
        runtime.stopping = true;
        runtime.workers.shutdownNow();
        try {
            runtime.workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @Extension
    public static final class Completed extends RunListener<Run<?, ?>> {
        @Override
        public void onStarted(Run<?, ?> run, TaskListener listener) {
            try {
                if (SlackJobProperty.eligible(run)
                        && SlackConfiguration.get().isConfigured()
                        && run.getStartTimeInMillis()
                                >= SlackConfiguration.get().getEnabledAtMillis()) captureExecutionContext(run);
            } catch (IOException | RuntimeException | LinkageError ignored) {
                // Missing execution identity keeps recovery unverified without affecting the build.
            }
        }

        @Override
        public void onFinalized(Run<?, ?> run) {
            try {
                if (SlackJobProperty.eligible(run) && SlackConfiguration.get().isConfigured())
                    get().schedule(run.getParent(), run.getNumber());
            } catch (RuntimeException | LinkageError ignored) {
                /* Optional notification must not affect finalization. */
            }
        }
    }

    @Extension
    public static final class Reconcile extends AsyncPeriodicWork {
        public Reconcile() {
            super("BCI Slack pending notifications");
        }

        @Override
        public long getRecurrencePeriod() {
            return 5000;
        }

        @Override
        protected void execute(TaskListener listener) {
            try (var ignored = ACL.as2(ACL.SYSTEM2)) {
                int count = 0;
                for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                    if (++count > 10000) break;
                    if (job.getProperty(SlackJobProperty.class) != null) get().schedule(job, 0);
                }
            }
        }
    }
}
