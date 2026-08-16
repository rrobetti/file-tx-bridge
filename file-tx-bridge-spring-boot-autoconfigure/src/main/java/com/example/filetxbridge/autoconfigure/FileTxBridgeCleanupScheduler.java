package com.example.filetxbridge.autoconfigure;

import com.example.filetxbridge.recovery.RecoveryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically calls {@link RecoveryManager#cleanupAbandonedTransactions(Duration)}
 * on a background daemon thread. Transaction-manager-agnostic: nothing here
 * depends on Atomikos, Bitronix, Narayana, or any other specific JTA
 * implementation -- it just needs a {@link RecoveryManager}.
 *
 * <p>{@code maxAge} here is an operational/forensic grace period, not a
 * correctness requirement: a tx directory with no PREPARED/COMMITTING/COMMITTED/
 * ROLLED_BACK flag AND no in-memory context on this resource instance is already
 * permanently unreachable by prepare() -- prepare() cannot succeed without a prior
 * start() on this exact instance, and the JVM that could have called start() on it
 * crashed (it never logged the branch with the transaction manager either, since
 * that only happens once prepare() is reached) -- so no future prepare() call can
 * ever land on it, at any age. The wait exists so a quickly-restarting,
 * crash-looping process does not destroy forensic evidence before anyone has a
 * chance to look at it, and as a cheap hedge in case some future code change ever
 * breaks the in-memory-context invariant this relies on. See {@link
 * RecoveryManager#cleanupAbandonedTransactions} for the full reasoning.
 */
public class FileTxBridgeCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(FileTxBridgeCleanupScheduler.class);

    private final RecoveryManager recoveryManager;
    private final Duration interval;
    private final Duration maxAge;
    private ScheduledExecutorService executor;

    public FileTxBridgeCleanupScheduler(RecoveryManager recoveryManager, Duration interval, Duration maxAge) {
        this.recoveryManager = recoveryManager;
        this.interval = interval;
        this.maxAge = maxAge;
    }

    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "file-tx-bridge-cleanup");
            thread.setDaemon(true);
            return thread;
        });
        long intervalMillis = interval.toMillis();
        executor.scheduleWithFixedDelay(this::runCleanup, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    private void runCleanup() {
        try {
            recoveryManager.cleanupAbandonedTransactions(maxAge);
        } catch (Exception e) {
            log.warn("Scheduled abandoned-transaction cleanup failed, will retry next interval", e);
        }
    }

    public void stop() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
