package com.example.filetxbridge.xa;

import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.core.FileTxContext;
import com.example.filetxbridge.core.FileOperation;
import com.example.filetxbridge.core.WriteMode;
import com.example.filetxbridge.util.DurabilityHelper;
import com.example.filetxbridge.util.XidUtils;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class FileXaSession {

    private final FileXaResource xaResource;
    private final FileResourceManager rm;
    private Xid currentXid;
    private final AtomicInteger opCounter = new AtomicInteger(0);

    public FileXaSession(FileResourceManager rm) {
        this.rm = rm;
        this.xaResource = new FileXaResource(rm);
    }

    public FileXaResource getXaResource() {
        return xaResource;
    }

    public void begin(Xid xid) throws XAException {
        this.currentXid = xid;
        this.opCounter.set(0);
        xaResource.start(xid, XAResource.TMNOFLAGS);
    }

    public void addCreateFile(Path targetPath, Path commitFlagPath, byte[] content, WriteMode mode)
            throws IOException, XAException {
        if (currentXid == null) {
            throw new IllegalStateException("No active transaction");
        }

        // Acquire the advisory lock for this target path before doing any work, so
        // a concurrent transaction targeting the same path waits (or fails loudly
        // on timeout) instead of racing this one -- see PathLockManager. Held until
        // this operation reaches a terminal state (commit()/rollback()/forget()
        // releases it); released here instead if registering the operation itself
        // never completes, so a failed addCreateFile() never leaks the lock.
        rm.getLockManager().lock(targetPath);
        boolean registered = false;
        try {
            String xidKey = XidUtils.toDirectoryName(currentXid);
            String opId = "op-" + opCounter.getAndIncrement();
            Path stagingFile = rm.getStagingDir().resolve(xidKey + "-" + opId + ".tmp");

            // Write content to staging file and fsync
            DurabilityHelper.writeAndFsync(stagingFile, content);

            // Resolve commitFlagPath: use target + ".committed" if null
            Path resolvedFlagPath = commitFlagPath != null
                    ? commitFlagPath
                    : targetPath.resolveSibling(targetPath.getFileName() + ".committed");

            FileOperation op = new FileOperation(opId, targetPath.toAbsolutePath(),
                    resolvedFlagPath.toAbsolutePath(), mode, content);

            Map<String, FileTxContext> contexts = xaResource.getContexts();
            FileTxContext ctx = contexts.get(xidKey);
            if (ctx == null) {
                throw new XAException(XAException.XAER_NOTA);
            }
            ctx.addOperation(op);
            registered = true;
        } finally {
            if (!registered) {
                rm.getLockManager().unlock(targetPath);
            }
        }
    }

    public void addCreateFile(Path targetPath, Path commitFlagPath, InputStream contentStream, WriteMode mode)
            throws IOException, XAException {
        if (currentXid == null) {
            throw new IllegalStateException("No active transaction");
        }

        // See the byte[] overload above for why this is acquired here and released
        // in the finally block only on failure -- on success it stays held until
        // commit()/rollback()/forget() resolves this operation.
        rm.getLockManager().lock(targetPath);
        boolean registered = false;
        try {
            String xidKey = XidUtils.toDirectoryName(currentXid);
            String opId = "op-" + opCounter.getAndIncrement();
            Path stagingFile = rm.getStagingDir().resolve(xidKey + "-" + opId + ".tmp");

            // Stream directly to staging file to avoid buffering large content in memory
            try (FileChannel ch = FileChannel.open(stagingFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                byte[] buf = new byte[8192];
                int read;
                while ((read = contentStream.read(buf)) != -1) {
                    ch.write(ByteBuffer.wrap(buf, 0, read));
                }
                ch.force(true);
            }

            Path resolvedFlagPath = commitFlagPath != null
                    ? commitFlagPath
                    : targetPath.resolveSibling(targetPath.getFileName() + ".committed");

            // Content byte[] is null for stream variant — staging file on disk is the source of truth
            FileOperation op = new FileOperation(opId, targetPath.toAbsolutePath(),
                    resolvedFlagPath.toAbsolutePath(), mode, null);

            Map<String, FileTxContext> contexts = xaResource.getContexts();
            FileTxContext ctx = contexts.get(xidKey);
            if (ctx == null) {
                throw new XAException(XAException.XAER_NOTA);
            }
            ctx.addOperation(op);
            registered = true;
        } finally {
            if (!registered) {
                rm.getLockManager().unlock(targetPath);
            }
        }
    }

    public void end() throws XAException {
        if (currentXid == null) {
            throw new IllegalStateException("No active transaction");
        }
        xaResource.end(currentXid, XAResource.TMSUCCESS);
    }
}
