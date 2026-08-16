package com.example.filetxbridge.autoconfigure;

import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.recovery.RecoveryManager;
import com.example.filetxbridge.xa.FileXaResource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Spring Boot auto-configuration for FileTxBridge.
 *
 * <p>Activated when {@link FileResourceManager} is present on the classpath (i.e. the
 * {@code file-tx-bridge} core JAR is a dependency). All beans are guarded by
 * {@link ConditionalOnMissingBean} so applications can supply custom implementations.
 *
 * <h2>Registered beans</h2>
 * <ul>
 *   <li>{@link FileResourceManager} — singleton; manages the rmHome directory layout.</li>
 *   <li>{@link FileXaResource} — singleton; the XAResource to enlist in JTA transactions.</li>
 *   <li>{@link RecoveryManager} — singleton; used for in-doubt transaction recovery.</li>
 *   <li>{@link FileTxStartupRecovery} — runs startup recovery on ApplicationReadyEvent
 *       (disable with {@code filetxbridge.startup.recovery-enabled=false}).</li>
 *   <li>{@link FileTxBridgeCleanupScheduler} — periodically sweeps abandoned
 *       pre-prepare transactions (opt-in via {@code filetxbridge.cleanup.enabled=true}).</li>
 * </ul>
 *
 * <h2>Configuration properties</h2>
 * See {@link FileTxBridgeProperties} for the full list of available properties.
 *
 * <h2>Spring Boot version compatibility</h2>
 * This module targets Spring Boot 3.x, which uses the {@code jakarta.transaction} namespace
 * matching the core library.
 */
@AutoConfiguration
@ConditionalOnClass(FileResourceManager.class)
@EnableConfigurationProperties(FileTxBridgeProperties.class)
public class FileTxBridgeAutoConfiguration {

    /**
     * Creates the {@link FileResourceManager} using the configured {@code rmHome} directory.
     * The directory (and its subdirectories) is created on first use if it does not exist.
     */
    @Bean
    @ConditionalOnMissingBean
    public FileResourceManager fileResourceManager(FileTxBridgeProperties props) throws IOException {
        return new FileResourceManager(Path.of(props.getRmHome()));
    }

    /**
     * Creates the {@link FileXaResource} backed by the auto-configured resource manager.
     * Enlist this bean into a JTA transaction via {@code transaction.enlistResource(fileXaResource)}.
     */
    @Bean
    @ConditionalOnMissingBean
    public FileXaResource fileXaResource(FileResourceManager rm) {
        return new FileXaResource(rm);
    }

    /**
     * Creates the {@link RecoveryManager} for in-doubt transaction recovery.
     * Used by {@link FileTxStartupRecovery} and available for manual recovery invocations.
     */
    @Bean
    @ConditionalOnMissingBean
    public RecoveryManager fileTxRecoveryManager(FileResourceManager rm, FileXaResource xaResource) {
        return new RecoveryManager(rm, xaResource);
    }

    /**
     * Registers the startup recovery listener unless
     * {@code filetxbridge.startup.recovery-enabled=false}.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "filetxbridge.startup", name = "recovery-enabled", matchIfMissing = true)
    public FileTxStartupRecovery fileTxStartupRecovery(RecoveryManager recoveryManager) {
        return new FileTxStartupRecovery(recoveryManager);
    }

    /**
     * Runs {@link RecoveryManager#cleanupAbandonedTransactions} on a schedule.
     * Opt-in via {@code filetxbridge.cleanup.enabled=true} -- new,
     * unproven-in-the-wild behaviour should never activate silently, even though
     * what it deletes is already permanently unreachable regardless of age (see
     * that method's javadoc). Transaction-manager-agnostic: works the same
     * whether the application uses Atomikos, Bitronix, Narayana, or any other JTA
     * implementation.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "filetxbridge.cleanup", name = "enabled", havingValue = "true")
    public FileTxBridgeCleanupScheduler fileTxBridgeCleanupScheduler(
            RecoveryManager recoveryManager, FileTxBridgeProperties props) {
        FileTxBridgeProperties.Cleanup cleanup = props.getCleanup();
        return new FileTxBridgeCleanupScheduler(recoveryManager, cleanup.getInterval(), cleanup.getMaxAge());
    }
}
