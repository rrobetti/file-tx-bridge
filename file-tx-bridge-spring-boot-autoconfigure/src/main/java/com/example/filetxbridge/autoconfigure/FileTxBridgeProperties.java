package com.example.filetxbridge.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration properties for FileTxBridge, bound to the {@code filetxbridge.*} namespace.
 *
 * <p>Example {@code application.properties}:
 * <pre>
 * filetxbridge.rm-home=/var/lib/my-app/file-tx
 * filetxbridge.startup.recovery-enabled=true
 * # filetxbridge.cleanup.enabled=false   # optional, defaults to true
 * # filetxbridge.cleanup.interval=1h     # optional, defaults to 1h
 * # filetxbridge.cleanup.max-age=24h     # optional, defaults to 24h
 * </pre>
 */
@ConfigurationProperties(prefix = "filetxbridge")
public class FileTxBridgeProperties {

    /**
     * Base directory for staging, tx, backup, and lock files.
     * Must reside on the same filesystem as all target file paths so that
     * atomic move ({@code ATOMIC_MOVE}) works during commit.
     */
    private String rmHome = System.getProperty("java.io.tmpdir") + "/file-tx-bridge";

    /**
     * Startup recovery settings.
     */
    private Startup startup = new Startup();

    /**
     * Scheduled abandoned-transaction cleanup settings. See
     * {@link com.example.filetxbridge.autoconfigure.FileTxBridgeCleanupScheduler}.
     */
    private Cleanup cleanup = new Cleanup();

    public String getRmHome() {
        return rmHome;
    }

    public void setRmHome(String rmHome) {
        this.rmHome = rmHome;
    }

    public Startup getStartup() {
        return startup;
    }

    public void setStartup(Startup startup) {
        this.startup = startup;
    }

    public Cleanup getCleanup() {
        return cleanup;
    }

    public void setCleanup(Cleanup cleanup) {
        this.cleanup = cleanup;
    }

    /**
     * Nested properties for startup recovery behaviour.
     */
    public static class Startup {

        /**
         * Whether to run {@link com.example.filetxbridge.recovery.RecoveryManager#performStartupRecovery()}
         * on {@link org.springframework.boot.context.event.ApplicationReadyEvent}.
         * Defaults to {@code true}.
         */
        private boolean recoveryEnabled = true;

        public boolean isRecoveryEnabled() {
            return recoveryEnabled;
        }

        public void setRecoveryEnabled(boolean recoveryEnabled) {
            this.recoveryEnabled = recoveryEnabled;
        }
    }

    /**
     * Nested properties for scheduled abandoned-transaction cleanup.
     */
    public static class Cleanup {

        /**
         * Whether to periodically call
         * {@link com.example.filetxbridge.recovery.RecoveryManager#cleanupAbandonedTransactions(Duration)}
         * in the background. Defaults to {@code true}: a tx directory this sweep
         * would ever touch is already permanently unreachable by prepare()
         * regardless of age (see that method's javadoc for why), so there is no
         * correctness reason to leave the underlying resource leak (the README's
         * "Resource leakage" limitation) unaddressed by default. Set to
         * {@code false} to disable if you would rather manage this yourself.
         */
        private boolean enabled = true;

        /**
         * How often to run the sweep. Defaults to 1 hour.
         */
        private Duration interval = Duration.ofHours(1);

        /**
         * How old an unflagged, not-in-memory-tracked tx directory must be before
         * it is swept. This is an operational/forensic grace period, not a
         * correctness requirement: such a tx directory is already permanently
         * unreachable by prepare() regardless of age (see {@code
         * RecoveryManager#cleanupAbandonedTransactions}'s javadoc for why). The
         * wait just gives a quickly-restarting, crash-looping process a window
         * before the evidence is gone. Defaults to 24 hours.
         */
        private Duration maxAge = Duration.ofHours(24);

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Duration getInterval() {
            return interval;
        }

        public void setInterval(Duration interval) {
            this.interval = interval;
        }

        public Duration getMaxAge() {
            return maxAge;
        }

        public void setMaxAge(Duration maxAge) {
            this.maxAge = maxAge;
        }
    }
}
