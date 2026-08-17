package com.example.filetxbridge.atomikos;

import com.atomikos.datasource.xa.XATransactionalResource;
import com.example.filetxbridge.xa.FileXaResource;

import javax.transaction.xa.XAResource;

/**
 * Registers a {@link FileXaResource} with Atomikos so its transactions survive a
 * restart.
 *
 * <p>Atomikos never persists an {@code XAResource} object itself in its own
 * transaction log -- only the resource's unique name -- so after a crash it needs a
 * factory it can ask for a fresh instance by name before driving
 * {@code recover()}/{@code commit()}/{@code rollback()} on it. Wrapping a
 * {@code FileXaResource} in {@link XATransactionalResource} and registering it via
 * {@code com.atomikos.icatch.config.Configuration.addResource(...)} is that factory.
 * Without it, {@code Transaction#enlistResource(FileXaResource)} is rejected with
 * "There is no registered resource that can recover the given XAResource instance."
 *
 * <p>Usage:
 * <pre>{@code
 * FileResourceManager rm = new FileResourceManager(rmHome);
 * FileXaResource xaResource = new FileXaResource(rm);
 * Configuration.addResource(
 *         new FileTxBridgeAtomikosResource("my-file-tx-bridge-resource", xaResource));
 *
 * // ... later, per transaction ...
 * tx.enlistResource(xaResource);
 * }</pre>
 *
 * <p>The unique resource name must stay the same across restarts of the JVM that
 * owns a given {@code rmHome} -- it is how Atomikos matches this registration back
 * to the in-doubt transactions it finds recorded in its own transaction log.
 */
public class FileTxBridgeAtomikosResource extends XATransactionalResource {

    private final XAResource xaResource;

    public FileTxBridgeAtomikosResource(String uniqueResourceName, FileXaResource xaResource) {
        super(uniqueResourceName);
        this.xaResource = xaResource;
    }

    @Override
    protected XAResource refreshXAConnection() {
        // FileXaResource holds no live connection to refresh (unlike a pooled JDBC
        // or JMS connection) -- it is a plain filesystem-backed resource, so the
        // same instance stays valid for the lifetime of this registration.
        return xaResource;
    }
}
