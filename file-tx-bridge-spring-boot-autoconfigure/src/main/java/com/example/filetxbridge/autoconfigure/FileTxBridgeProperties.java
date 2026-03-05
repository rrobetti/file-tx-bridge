package com.example.filetxbridge.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for FileTxBridge, bound to the {@code filetxbridge.*} namespace.
 *
 * <p>Example {@code application.properties}:
 * <pre>
 * filetxbridge.rm-home=/var/lib/my-app/file-tx
 * filetxbridge.startup.recovery-enabled=true
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
}
