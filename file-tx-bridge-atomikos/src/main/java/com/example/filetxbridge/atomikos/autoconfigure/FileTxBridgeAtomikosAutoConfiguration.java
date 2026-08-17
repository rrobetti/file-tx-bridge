package com.example.filetxbridge.atomikos.autoconfigure;

import com.atomikos.datasource.xa.XATransactionalResource;
import com.example.filetxbridge.xa.FileXaResource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration that registers a {@link FileXaResource} bean with
 * Atomikos automatically.
 *
 * <p>Activated only when BOTH {@link FileXaResource} (the {@code file-tx-bridge}
 * core JAR) and Atomikos's {@link XATransactionalResource} (the
 * {@code com.atomikos:transactions-jta} JAR) are on the classpath, AND a
 * {@code FileXaResource} bean already exists -- typically supplied by
 * {@code file-tx-bridge-spring-boot-autoconfigure}, or defined manually by the
 * application. If no such bean exists, this auto-configuration simply does not
 * activate; it never fails startup for lacking one.
 *
 * <p>Without this registration, {@code Transaction#enlistResource(FileXaResource)}
 * is rejected by Atomikos with "no registered resource that can recover the given
 * XAResource instance" -- see {@link com.example.filetxbridge.atomikos.FileTxBridgeAtomikosResource}
 * for why.
 *
 * <h2>Configuration properties</h2>
 * See {@link FileTxBridgeAtomikosProperties} for the full list of available properties.
 */
@AutoConfiguration
@ConditionalOnClass({FileXaResource.class, XATransactionalResource.class})
@EnableConfigurationProperties(FileTxBridgeAtomikosProperties.class)
public class FileTxBridgeAtomikosAutoConfiguration {

    /**
     * Registers the {@link FileXaResource} bean with Atomikos on startup and
     * removes it on shutdown, via {@link FileTxBridgeAtomikosRegistration}'s
     * init/destroy methods.
     */
    @Bean(initMethod = "register", destroyMethod = "unregister")
    @ConditionalOnBean(FileXaResource.class)
    @ConditionalOnMissingBean
    public FileTxBridgeAtomikosRegistration fileTxBridgeAtomikosRegistration(
            FileXaResource xaResource, FileTxBridgeAtomikosProperties props) {
        return new FileTxBridgeAtomikosRegistration(props.getResourceName(), xaResource);
    }
}
