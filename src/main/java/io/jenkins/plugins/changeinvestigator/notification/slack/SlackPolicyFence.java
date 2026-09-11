package io.jenkins.plugins.changeinvestigator.notification.slack;

import hudson.model.Job;
import hudson.util.TextFile;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;

/** A local disable watermark prevents a quick off/on cycle from replaying stale queued work. */
public final class SlackPolicyFence {
    private SlackPolicyFence() {}

    public record Fence(long at, int afterBuild) {}

    public static Fence read(Job<?, ?> job) throws IOException {
        var path = job.getRootDir().toPath().resolve("bci-slack-disabled-after");
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return new Fence(0, 0);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 64)
            throw new IOException("Invalid Slack policy watermark");
        String[] fields = Files.readString(path).trim().split(":");
        try {
            if (fields.length != 2) throw new IllegalArgumentException();
            long at = Long.parseLong(fields[0]);
            int after = Integer.parseInt(fields[1]);
            if (at < 0 || after < 0) throw new IllegalArgumentException();
            return new Fence(at, after);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid Slack policy watermark");
        }
    }

    public static void disabled(Job<?, ?> job) throws IOException {
        synchronized (job) {
            if (!Files.isRegularFile(job.getRootDir().toPath().resolve("bci-slack-active"), LinkOption.NOFOLLOW_LINKS))
                return;
            Fence prior = read(job);
            int after = job.getLastBuild() == null ? 0 : job.getLastBuild().getNumber();
            new TextFile(job.getRootDir()
                            .toPath()
                            .resolve("bci-slack-disabled-after")
                            .toFile())
                    .write(Math.max(System.currentTimeMillis(), prior.at() + 1) + ":"
                            + Math.max(after, prior.afterBuild()));
        }
    }
}
