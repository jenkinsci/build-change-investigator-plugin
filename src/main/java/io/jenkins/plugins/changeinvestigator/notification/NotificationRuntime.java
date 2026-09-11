package io.jenkins.plugins.changeinvestigator.notification;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.jenkins.plugins.changeinvestigator.notification.identity.StableIdentities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import jenkins.model.Jenkins;

/** Bounded evidence collection; external delivery runs on separate workers. */
@Extension
public final class NotificationRuntime extends RunListener<Run<?, ?>> {
    private static final Logger LOGGER = Logger.getLogger(NotificationRuntime.class.getName());
    private final AtomicLong rejected = new AtomicLong();
    private final UUID bootId = UUID.randomUUID();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2,
            2,
            30,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(250),
            runnable -> {
                Thread thread = new Thread(runnable, "bci-notification-evidence");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy());

    /** Administrator activation of local evidence collection; this does not approve delivery. */
    public void arm(Job<?, ?> job) throws IOException {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        StableIdentities.controllerId();
        String id = StableIdentities.jobId(job);
        long boundary = job.getLastBuild() == null ? 0 : job.getLastBuild().getNumber();
        new NotificationEngine(
                        job.getRootDir().toPath(),
                        UUID.fromString(id),
                        Jenkins.get().getRootDir().toPath(),
                        UUID.fromString(StableIdentities.controllerId()))
                .arm(boundary);
        Files.writeString(job.getRootDir().toPath().resolve("bci-notifications/core-armed"), Long.toString(boundary));
    }

    /** Activates local collection only for already approved disclosure policy; never approves delivery. */
    public void activateApproved(Job<?, ?> job) throws IOException {
        var latest = job.getLastBuild();
        activateApproved(job, latest == null ? 0 : latest.getNumber() - (latest.isBuilding() ? 1L : 0L));
    }

    private void activateApproved(Job<?, ?> job, long after) throws IOException {
        synchronized (job) {
            boolean slack = !io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackConfiguration.get()
                    .resolvedDestinations(job)
                    .isEmpty();
            boolean email = !io.jenkins.plugins.changeinvestigator.notification.email.config.EmailConfiguration.get()
                    .resolvedDestinations(job)
                    .isEmpty();
            if (!slack && !email) return;
            if (!armed(job)) {
                String id = StableIdentities.jobId(job);
                new NotificationEngine(
                                job.getRootDir().toPath(),
                                UUID.fromString(id),
                                Jenkins.get().getRootDir().toPath(),
                                UUID.fromString(StableIdentities.controllerId()))
                        .arm(after);
                Files.writeString(
                        job.getRootDir().toPath().resolve("bci-notifications/core-armed"), Long.toString(after));
            }
            for (String channel : List.of("slack", "email")) {
                if ((channel.equals("slack") && !slack) || (channel.equals("email") && !email)) continue;
                var marker = job.getRootDir().toPath().resolve("bci-" + channel + "-active");
                if (!Files.exists(marker, LinkOption.NOFOLLOW_LINKS)) Files.writeString(marker, Long.toString(after));
            }
        }
    }

    @Override
    public void onStarted(Run<?, ?> run, TaskListener listener) {
        try {
            if ((io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackConfiguration.get()
                            .resolvedDestinations(run.getParent())
                            .isEmpty()
                    && io.jenkins.plugins.changeinvestigator.notification.email.config.EmailConfiguration.get()
                            .resolvedDestinations(run.getParent())
                            .isEmpty())) return;
            String name = run.getParent().getFullName();
            long before = run.getNumber() - 1L;
            workers.execute(() -> {
                try {
                    Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
                    if (job != null) activateApproved(job, before);
                } catch (IOException | RuntimeException | LinkageError e) {
                    LOGGER.warning("Approved notification activation deferred");
                }
            });
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warning("Approved notification activation deferred");
        }
    }

    @Override
    public void onFinalized(Run<?, ?> run) {
        try {
            if (!armed(run.getParent())) return;
            schedule(run.getParent().getFullName());
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warning("Notification observation scheduling unavailable");
        }
    }

    public boolean schedule(String jobName) {
        try {
            workers.execute(() -> process(jobName));
            return true;
        } catch (java.util.concurrent.RejectedExecutionException e) {
            rejected.incrementAndGet();
            return false;
        }
    }

    /** Delivery remains paused until an explicit administrator approval for this boot. */
    public io.jenkins.plugins.changeinvestigator.notification.identity.ControllerDeliveryGuard deliveryGuard()
            throws IOException {
        return new io.jenkins.plugins.changeinvestigator.notification.identity.ControllerDeliveryGuard(
                Jenkins.get().getRootDir().toPath(), UUID.fromString(StableIdentities.controllerId()), bootId);
    }

    public int queuedWork() {
        return workers.getQueue().size();
    }

    boolean idle() {
        return workers.getQueue().isEmpty() && workers.getActiveCount() == 0;
    }

    public long rejectedWork() {
        return rejected.get();
    }

    private void process(String name) {
        try {
            Job<?, ?> job = Jenkins.get().getItemByFullName(name, Job.class);
            if (job == null || !armed(job)) return;
            String jobId = StableIdentities.jobId(job);
            var engine = new NotificationEngine(
                    job.getRootDir().toPath(),
                    UUID.fromString(jobId),
                    Jenkins.get().getRootDir().toPath(),
                    UUID.fromString(StableIdentities.controllerId()));
            long boundary =
                    Long.parseLong(Files.readString(job.getRootDir().toPath().resolve("bci-notifications/core-armed")));
            List<Run<?, ?>> runs = new java.util.ArrayList<>();
            Run<?, ?> cursor = job.getLastBuild();
            for (int count = 0; cursor != null && count < 100; count++, cursor = cursor.getPreviousBuild())
                runs.add(cursor);
            java.util.Collections.reverse(runs);
            long previous = boundary;
            boolean orderingGap = false;
            for (Run<?, ?> run : runs) {
                if (run.getNumber() <= boundary) continue;
                if (run.isBuilding() || run.isLogUpdated()) {
                    orderingGap = true;
                    continue;
                }
                if (orderingGap
                        && holdForEarlierRun(run.getStartTimeInMillis(), run.getDuration(), System.currentTimeMillis()))
                    continue;
                String runId = StableIdentities.runId(run);
                var configuration =
                        io.jenkins.plugins.changeinvestigator.notification.slack.config.SlackConfiguration.get();
                var policy = configuration.policy(job);
                var fence = io.jenkins.plugins.changeinvestigator.notification.slack.SlackPolicyFence.read(job);
                var destinations = new java.util.ArrayList<>(configuration.resolvedDestinations(job).stream()
                        .filter(d -> run.getNumber() > fence.afterBuild())
                        .map(d -> new NotificationEngine.DestinationPolicy(
                                d.identity(), d.getGeneration(), policy.recovery(), policy.aiOnly()))
                        .toList());
                var emailConfiguration =
                        io.jenkins.plugins.changeinvestigator.notification.email.config.EmailConfiguration.get();
                var emailPolicy = emailConfiguration.policy(job);
                var emailFence = io.jenkins.plugins.changeinvestigator.notification.email.EmailPolicyFence.read(job);
                Path emailMarker = job.getRootDir().toPath().resolve("bci-email-active");
                long emailActivation = Files.isRegularFile(emailMarker, LinkOption.NOFOLLOW_LINKS)
                        ? Long.parseLong(Files.readString(emailMarker))
                        : Long.MAX_VALUE;
                if (emailActivation < 0) throw new IOException("Invalid email activation boundary");
                var emailDestinations = emailConfiguration.resolvedDestinations(job);
                var emailSecrets = new java.util.ArrayList<String>();
                for (var destination : emailDestinations) {
                    emailConfiguration
                            .resolveSettings(job, destination)
                            .map(settings -> settings.password())
                            .filter(password -> !password.isEmpty())
                            .ifPresent(emailSecrets::add);
                }
                destinations.addAll(emailDestinations.stream()
                        .filter(d -> run.getNumber() > emailFence.afterBuild() && run.getNumber() > emailActivation)
                        .map(d -> new NotificationEngine.DestinationPolicy(
                                d.identity(), d.getGeneration(), emailPolicy.recovery(), emailPolicy.aiOnly(), "EMAIL"))
                        .toList());
                engine.ingestConfigured(
                        JenkinsNotificationAdapter.observe(
                                run, jobId, runId, !orderingGap && previous == run.getNumber() - 1),
                        destinations,
                        System.currentTimeMillis(),
                        value -> emailSecrets.stream()
                                .anyMatch(
                                        secret -> io.jenkins.plugins.changeinvestigator.notification.email.EmailMessage
                                                .containsSecret(value, secret)));
                if (destinations.stream().anyMatch(d -> "SLACK".equals(d.transport())))
                    io.jenkins.plugins.changeinvestigator.notification.slack.SlackDispatcher.get()
                            .schedule(name);
                if (destinations.stream().anyMatch(d -> "EMAIL".equals(d.transport())))
                    io.jenkins.plugins.changeinvestigator.notification.email.EmailDispatcher.get()
                            .schedule(name);
                previous = run.getNumber();
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            LOGGER.warning("Notification processing paused; retained observations require reconciliation");
        }
    }

    /** A persisted completion clock bounds out-of-order holding without restart-reset deadlines. */
    static boolean holdForEarlierRun(long startedAt, long duration, long now) {
        if (startedAt < 0 || duration < 0 || now < 0 || startedAt > Long.MAX_VALUE - duration) return true;
        long completedAt = startedAt + duration;
        if (now < completedAt) return true;
        return now - completedAt < 120_000;
    }

    @hudson.init.Terminator
    public static void shutdown() {
        Jenkins instance = Jenkins.getInstanceOrNull();
        if (instance != null)
            for (NotificationRuntime runtime : instance.getExtensionList(NotificationRuntime.class))
                runtime.workers.shutdownNow();
    }

    private static boolean armed(Job<?, ?> job) {
        return Files.isRegularFile(
                        job.getRootDir().toPath().resolve("bci-notification-job-id"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(
                        job.getRootDir().toPath().resolve("bci-notifications/core-armed"), LinkOption.NOFOLLOW_LINKS);
    }

    @Extension
    public static final class Reconciler extends AsyncPeriodicWork {
        public Reconciler() {
            super("Build Change Investigator notification reconciliation");
        }

        @Override
        public long getRecurrencePeriod() {
            return 60_000;
        }

        @Override
        protected void execute(TaskListener listener) {
            NotificationRuntime runtime = hudson.ExtensionList.lookupSingleton(NotificationRuntime.class);
            for (Job<?, ?> job : Jenkins.get().getAllItems(Job.class)) {
                if (armed(job) && !runtime.schedule(job.getFullName())) break;
            }
        }
    }
}
