package com.example.filetxbridge.atomikos.autoconfigure;

import com.atomikos.icatch.config.Configuration;
import com.example.filetxbridge.atomikos.FileTxBridgeAtomikosResource;
import com.example.filetxbridge.xa.FileXaResource;

/**
 * Lifecycle wrapper that registers a {@link FileTxBridgeAtomikosResource} with
 * Atomikos on {@link #register()} and removes it on {@link #unregister()}. Wired as
 * a bean's init/destroy methods by {@link FileTxBridgeAtomikosAutoConfiguration} so
 * the registration follows the Spring application context's own lifecycle.
 */
public class FileTxBridgeAtomikosRegistration {

    private final String uniqueResourceName;
    private final FileTxBridgeAtomikosResource resource;

    public FileTxBridgeAtomikosRegistration(String uniqueResourceName, FileXaResource xaResource) {
        this.uniqueResourceName = uniqueResourceName;
        this.resource = new FileTxBridgeAtomikosResource(uniqueResourceName, xaResource);
    }

    public void register() {
        Configuration.addResource(resource);
    }

    public void unregister() {
        Configuration.removeResource(uniqueResourceName);
    }
}
