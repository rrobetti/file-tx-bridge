package com.example.filetxbridge.autoconfigure;

import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.recovery.RecoveryManager;
import com.example.filetxbridge.xa.FileXaResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link FileTxBridgeAutoConfiguration} using Spring Boot's
 * {@link ApplicationContextRunner} — no full Spring application context is started.
 */
class FileTxBridgeAutoConfigurationTest {

    @TempDir
    Path tempDir;

    /** Builds an ApplicationContextRunner pre-loaded with this auto-configuration. */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FileTxBridgeAutoConfiguration.class))
                .withPropertyValues("filetxbridge.rm-home=" + tempDir.toAbsolutePath());
    }

    @Test
    void allBeansAreRegisteredByDefault() {
        contextRunner().run(context -> {
            assertThat(context).hasSingleBean(FileResourceManager.class);
            assertThat(context).hasSingleBean(FileXaResource.class);
            assertThat(context).hasSingleBean(RecoveryManager.class);
            assertThat(context).hasSingleBean(FileTxStartupRecovery.class);
        });
    }

    @Test
    void startupRecoveryBeanIsAbsentWhenDisabled() {
        contextRunner()
                .withPropertyValues("filetxbridge.startup.recovery-enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(FileTxStartupRecovery.class));
    }

    @Test
    void customFileResourceManagerBeanIsRespected() {
        contextRunner()
                .withBean("customRm", FileResourceManager.class,
                        () -> {
                            try {
                                return new FileResourceManager(tempDir.resolve("custom"));
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        })
                .run(context -> {
                    // Only one FileResourceManager bean — the user-defined one wins
                    assertThat(context).hasSingleBean(FileResourceManager.class);
                    assertThat(context.getBean(FileResourceManager.class).getRmHome())
                            .isEqualTo(tempDir.resolve("custom"));
                });
    }

    @Test
    void rmHomePropertyIsAppliedToBean() {
        Path customHome = tempDir.resolve("my-rm-home");
        contextRunner()
                .withPropertyValues("filetxbridge.rm-home=" + customHome.toAbsolutePath())
                .run(context -> assertThat(context.getBean(FileResourceManager.class).getRmHome())
                        .isEqualTo(customHome));
    }
}
