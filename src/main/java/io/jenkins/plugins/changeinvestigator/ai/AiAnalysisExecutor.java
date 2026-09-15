package io.jenkins.plugins.changeinvestigator.ai;

import hudson.Extension;
import hudson.ExtensionList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Bounded shared execution for manual and notification analysis requests. */
@Extension
public final class AiAnalysisExecutor {
    private volatile boolean stopping;
    private final java.util.Map<String, java.util.concurrent.CompletableFuture<AiAssessment>> inFlight =
            new java.util.HashMap<>();

    /** A controller-local reservation survives action deserialization, but never a controller restart. */
    public record Reservation(java.util.concurrent.CompletableFuture<AiAssessment> future, boolean owner) {}

    public synchronized java.util.concurrent.CompletableFuture<AiAssessment> find(String key) {
        return inFlight.get(key);
    }

    public synchronized Reservation reserve(String key) {
        var existing = inFlight.get(key);
        if (existing != null) return new Reservation(existing, false);
        if (stopping || inFlight.size() >= 34) return null;
        var future = new java.util.concurrent.CompletableFuture<AiAssessment>();
        inFlight.put(key, future);
        return new Reservation(future, true);
    }

    public void complete(String key, java.util.concurrent.CompletableFuture<AiAssessment> future, AiAssessment result) {
        synchronized (this) {
            inFlight.remove(key, future);
        }
        future.complete(result);
    }

    private final ThreadPoolExecutor workers =
            new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32), work -> {
                Thread thread = new Thread(work, "BCI analysis");
                thread.setDaemon(true);
                return thread;
            });

    public static AiAnalysisExecutor get() {
        return ExtensionList.lookupSingleton(AiAnalysisExecutor.class);
    }

    public boolean isStopping() {
        return stopping;
    }

    public boolean submit(Runnable work) {
        if (stopping) return false;
        try {
            workers.execute(work);
            return true;
        } catch (java.util.concurrent.RejectedExecutionException unavailable) {
            return false;
        }
    }

    @hudson.init.Terminator
    public void stop() {
        stopping = true;
        java.util.List<java.util.concurrent.CompletableFuture<AiAssessment>> pending;
        synchronized (this) {
            pending = new java.util.ArrayList<>(inFlight.values());
            inFlight.clear();
        }
        for (var future : pending)
            future.complete(AiAssessment.failed("AI analysis stopped during controller shutdown."));
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
