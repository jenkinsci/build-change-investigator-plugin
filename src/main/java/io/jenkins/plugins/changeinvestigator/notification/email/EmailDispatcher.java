package io.jenkins.plugins.changeinvestigator.notification.email;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.Job;
import hudson.model.TaskListener;
import hudson.model.listeners.ItemListener;
import hudson.security.ACL;
import io.jenkins.plugins.changeinvestigator.notification.NotificationEngine;
import io.jenkins.plugins.changeinvestigator.notification.NotificationRuntime;
import io.jenkins.plugins.changeinvestigator.notification.email.config.EmailConfiguration;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import java.net.URI;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import jenkins.model.Jenkins;

/** Fair job-reference scheduling, separate from evidence ingestion and ordinary page rendering. */
@Extension
public final class EmailDispatcher extends AsyncPeriodicWork {
    private static final java.util.logging.Logger LOGGER =
            java.util.logging.Logger.getLogger(EmailDispatcher.class.getName());
    private final Set<String> scheduled = ConcurrentHashMap.newKeySet();
    private final Set<String> channels = ConcurrentHashMap.newKeySet();
    private final AtomicLong rejected = new AtomicLong();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(250),
            runnable -> {
                Thread t = new Thread(runnable, "bci-email-delivery");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());
    private int cursor;
    private final Clock clock;
    private final EmailOutbox.Sender sender;

    public EmailDispatcher() {
        this(Clock.systemUTC(), new EmailTransport()::send);
    }

    EmailDispatcher(Clock clock, EmailOutbox.Sender sender) {
        super("Build Change Investigator Email dispatch");
        this.clock = clock;
        this.sender = sender;
    }

    void stop() {
        workers.shutdownNow();
    }

    boolean idle() {
        return scheduled.isEmpty() && workers.getActiveCount() == 0;
    }

    @Override
    public long getRecurrencePeriod() {
        return 1000;
    }

    public static EmailDispatcher get() {
        return ExtensionList.lookupSingleton(EmailDispatcher.class);
    }

    public static void configurationChanged() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j == null) return;
        try {
            for (Job<?, ?> job : j.getAllItems(Job.class)) get().schedule(job.getFullName());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warning("Email policy refresh deferred");
        }
    }

    public int getQueuedWork() {
        return workers.getQueue().size();
    }

    public long getRejectedWork() {
        return rejected.get();
    }

    public boolean schedule(String jobName) {
        if (!scheduled.add(jobName)) return true;
        try {
            workers.execute(() -> {
                try {
                    process(jobName);
                } finally {
                    scheduled.remove(jobName);
                }
            });
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            scheduled.remove(jobName);
            rejected.incrementAndGet();
            return false;
        }
    }

    @Override
    protected synchronized void execute(TaskListener listener) {
        List<Job> jobs = Jenkins.get().getAllItems(Job.class);
        if (jobs.isEmpty()) return;
        // The cursor advances even on saturation, so one noisy prefix cannot monopolize the queue.
        for (int n = 0; n < Math.min(jobs.size(), 250); n++) {
            Job<?, ?> job = jobs.get(Math.floorMod(cursor++, jobs.size()));
            if (!schedule(job.getFullName())) break;
        }
    }

    private void process(String name) {
        try (var ignored = ACL.as2(ACL.SYSTEM2)) {
            Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
            if (job == null) return;
            var runtime = ExtensionList.lookupSingleton(NotificationRuntime.class);
            var config = EmailConfiguration.get();
            if (!config.resolvedDestinations(job).isEmpty()) runtime.activateApproved(job);
            if (!java.nio.file.Files.isRegularFile(
                    job.getRootDir().toPath().resolve("bci-email-active"), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                return;
            var identity = job.getRootDir().toPath().resolve("bci-notification-job-id");
            if (!java.nio.file.Files.isRegularFile(identity, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
            UUID jobId = UUID.fromString(StableIdentities.jobId(job));
            UUID controllerId = UUID.fromString(StableIdentities.controllerId());
            var engine = new NotificationEngine(
                    job.getRootDir().toPath(), jobId, Jenkins.get().getRootDir().toPath(), controllerId);
            var records = new ArrayList<>(engine.records());
            records.sort(java.util.Comparator.comparingInt(r -> r.lifecycle().status()
                            == io.jenkins.plugins.changeinvestigator.notification.lifecycle.LifecycleStatus.RECOVERED
                    ? 0
                    : 1));
            var budget = new EmailSubmissionBudget(Jenkins.get().getRootDir().toPath(), controllerId);
            var outbox = new EmailOutbox(clock, budget, sender);
            for (var record : records)
                for (var state : record.destinations()) {
                    if (!"EMAIL".equals(state.transport())) continue;
                    long now = clock.millis();
                    if (state.intents().stream()
                            .noneMatch(i -> (i.state()
                                                            == io.jenkins.plugins.changeinvestigator.notification
                                                                    .persistence.OutboxIntent.State.QUEUED
                                                    || i.state()
                                                            == io.jenkins.plugins.changeinvestigator.notification
                                                                    .persistence.OutboxIntent.State.RETRY_WAIT)
                                            && i.nextAttemptAt() <= now
                                    || i.state()
                                                    == io.jenkins.plugins.changeinvestigator.notification.persistence
                                                            .OutboxIntent.State.LEASED
                                            && i.leaseExpiresAt() <= now)) continue;
                    var destination = config.authorize(job, state.destinationId(), state.generation());
                    var fence = EmailPolicyFence.read(job);
                    if (fence.at() > 0)
                        engine.updateDestination(
                                record.investigationId(),
                                state.destinationId(),
                                d -> new io.jenkins.plugins.changeinvestigator.notification
                                        .NotificationInvestigationRecord.DestinationState(
                                        d.destinationId(),
                                        d.generation(),
                                        d.policy(),
                                        d.intents().stream()
                                                .map(i -> i.createdAt() <= fence.at()
                                                        ? i.cancelForDestinationGeneration(0)
                                                        : i)
                                                .toList(),
                                        d.submissions(),
                                        d.transport()),
                                "DISABLED_POLICY_FENCE",
                                now);
                    String channel = destination
                            .map(d -> d.getRecipient())
                            .orElse(state.destinationId().toString());
                    if (!channels.add(channel)) continue;
                    try {
                        outbox.dispatch(
                                engine,
                                record.investigationId(),
                                state.destinationId(),
                                () -> {
                                    // Current item identity and disclosure scope are resolved before every attempt.
                                    Job<?, ?> current = Jenkins.get().getItemByFullName(name, Job.class);
                                    if (current != job) return java.util.Optional.empty();
                                    try {
                                        if (EmailPolicyFence.read(job).at() > fence.at())
                                            return java.util.Optional.empty();
                                    } catch (java.io.IOException invalidFence) {
                                        return java.util.Optional.empty();
                                    }
                                    var live = config.authorize(job, state.destinationId(), state.generation());
                                    if (live.isEmpty()) return java.util.Optional.empty();
                                    var secret = config.resolveSettings(job, live.get());
                                    if (secret.isEmpty()) return java.util.Optional.empty();
                                    var policy = config.policy(job);
                                    String root = Jenkins.get().getRootUrl();
                                    if (root == null) return java.util.Optional.empty();
                                    var d = live.get();
                                    return java.util.Optional.of(new EmailOutbox.Target(
                                            d.getGeneration(),
                                            d.getSender(),
                                            d.getRecipient(),
                                            URI.create(root),
                                            secret.get(),
                                            policy.recovery(),
                                            policy.aiOnly()));
                                },
                                () -> {
                                    try {
                                        return runtime.deliveryGuard().canDispatch();
                                    } catch (java.io.IOException e) {
                                        return false;
                                    }
                                });
                    } finally {
                        channels.remove(channel);
                    }
                    // One case/destination per job turn; other queued jobs receive their opportunity first.
                    return;
                }
        } catch (java.io.IOException | RuntimeException | LinkageError e) {
            LOGGER.warning("Email dispatch paused; durable delivery state retained for review");
        }
    }

    @hudson.init.Terminator
    public static void shutdown() {
        Jenkins j = Jenkins.getInstanceOrNull();
        if (j != null)
            for (EmailDispatcher dispatcher : j.getExtensionList(EmailDispatcher.class))
                dispatcher.workers.shutdownNow();
    }

    @Extension
    public static final class PolicyChanges extends ItemListener {
        @Override
        public void onUpdated(Item item) {
            if (item instanceof Job<?, ?> job) {
                try {
                    if (EmailConfiguration.get().policy(job).destinationIds().isEmpty()) EmailPolicyFence.disabled(job);
                } catch (java.io.IOException | RuntimeException | LinkageError e) {
                    LOGGER.warning("Email policy watermark requires review");
                }
                get().schedule(job.getFullName());
            }
        }

        @Override
        public void onCreated(Item item) {
            if (item instanceof Job<?, ?> job) get().schedule(job.getFullName());
        }
    }
}
