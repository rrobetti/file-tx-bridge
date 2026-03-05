package com.example.filetxbridge.autoconfigure;

import com.example.filetxbridge.recovery.RecoveryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Runs {@link RecoveryManager#performStartupRecovery()} once the application context is fully
 * started. Registered automatically by {@link FileTxBridgeAutoConfiguration} unless
 * {@code filetxbridge.startup.recovery-enabled=false}.
 *
 * <p>Firing on {@link ApplicationReadyEvent} (rather than {@code ContextRefreshedEvent}) ensures
 * that the JTA transaction manager is completely initialised before the recovery scan begins.
 */
public class FileTxStartupRecovery implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(FileTxStartupRecovery.class);

    private final RecoveryManager recoveryManager;

    public FileTxStartupRecovery(RecoveryManager recoveryManager) {
        this.recoveryManager = recoveryManager;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Running FileTxBridge startup recovery...");
        recoveryManager.performStartupRecovery();
        log.info("FileTxBridge startup recovery complete.");
    }
}
