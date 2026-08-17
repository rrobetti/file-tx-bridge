package com.example.filetxbridge.atomikos.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the Atomikos registration, bound to the
 * {@code filetxbridge.atomikos.*} namespace.
 *
 * <p>Example {@code application.properties}:
 * <pre>
 * filetxbridge.atomikos.resource-name=my-app-file-tx-bridge
 * </pre>
 */
@ConfigurationProperties(prefix = "filetxbridge.atomikos")
public class FileTxBridgeAtomikosProperties {

    /**
     * The unique resource name Atomikos registers this resource under. Must stay
     * the same across restarts of the JVM that owns a given rmHome -- it is how
     * Atomikos matches this registration back to the in-doubt transactions it
     * finds recorded in its own transaction log.
     */
    private String resourceName = "file-tx-bridge";

    public String getResourceName() {
        return resourceName;
    }

    public void setResourceName(String resourceName) {
        this.resourceName = resourceName;
    }
}
