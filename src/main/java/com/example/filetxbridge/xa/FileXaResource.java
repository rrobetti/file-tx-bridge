package com.example.filetxbridge.xa;

import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.core.FileTxContext;
import com.example.filetxbridge.core.FileOperation;
import com.example.filetxbridge.core.TxState;
import com.example.filetxbridge.core.WriteMode;
import com.example.filetxbridge.recovery.TxMetadata;
import com.example.filetxbridge.util.DurabilityHelper;
import com.example.filetxbridge.util.XidUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FileXaResource implements XAResource {

    private static final Logger log = LoggerFactory.getLogger(FileXaResource.class);

    public static final String FLAG_PREPARED = "PREPARED";
    public static final String FLAG_COMMITTING = "COMMITTING";
    public static final String FLAG_COMMITTED = "COMMITTED";
    public static final String FLAG_ROLLING_BACK = "ROLLING_BACK";
    public static final String FLAG_ROLLED_BACK = "ROLLED_BACK";
    public static final String FLAG_HEURISTIC_HAZARD = "HEURISTIC_HAZARD";
    public static final String META_FILE = "meta.properties";

    private final FileResourceManager rm;
    private final Map<String, FileTxContext> contexts = new ConcurrentHashMap<>();
    private int transactionTimeout = 300;

    public FileXaResource(FileResourceManager rm) {
        this.rm = rm;
    }

    // -------------------------------------------------------------------------
    // XAResource lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void start(Xid xid, int flags) throws XAException {
        String key = XidUtils.toDirectoryName(xid);
        if (flags == TMNOFLAGS) {
            if (contexts.containsKey(key)) {
                throw new XAException(XAException.XAER_DUPID);
            }
            Path txDir = rm.getTxDir(xid);
            try {
                Files.createDirectories(txDir);
            } catch (IOException e) {
                log.error("Failed to create txDir {}", txDir, e);
                XAException xa = new XAException(XAException.XAER_RMERR);
                xa.initCause(e);
                throw xa;
            }
            contexts.put(key, new FileTxContext(xid, txDir));
        } else if (flags == TMJOIN || flags == TMRESUME) {
            if (!contexts.containsKey(key)) {
                throw new XAException(XAException.XAER_NOTA);
            }
        } else {
            throw new XAException(XAException.XAER_INVAL);
        }
    }

    @Override
    public void end(Xid xid, int flags) throws XAException {
        FileTxContext ctx = getContext(xid);
        ctx.setState(TxState.ENDED);
    }

    @Override
    public int prepare(Xid xid) throws XAException {
        FileTxContext ctx = getContext(xid);
        Path txDir = ctx.getTxDir();

        try {
            // Build and persist metadata
            List<TxMetadata.OpMetadata> opMetas = new ArrayList<>();
            for (FileOperation op : ctx.getOperations()) {
                opMetas.add(new TxMetadata.OpMetadata(
                        op.getOpId(),
                        op.getTargetPath().toAbsolutePath().toString(),
                        stagingPath(ctx, op).toAbsolutePath().toString(),
                        op.getCommitFlagPath().toAbsolutePath().toString(),
                        op.getMode().name()
                ));
            }
            String xidDirName = XidUtils.toDirectoryName(xid);
            TxMetadata meta = new TxMetadata(xidDirName, xid, opMetas);
            Path metaFile = txDir.resolve(META_FILE);
            meta.saveTo(metaFile);
            DurabilityHelper.fsyncFile(metaFile);
            DurabilityHelper.fsyncDir(txDir);

            // Create PREPARED flag
            DurabilityHelper.createFlagFile(txDir.resolve(FLAG_PREPARED));
            ctx.setState(TxState.PREPARED);
            return XA_OK;
        } catch (IOException e) {
            log.error("prepare failed for xid {}", XidUtils.toDirectoryName(xid), e);
            XAException xa = new XAException(XAException.XAER_RMERR);
            xa.initCause(e);
            throw xa;
        }
    }

    @Override
    public void commit(Xid xid, boolean onePhase) throws XAException {
        String key = XidUtils.toDirectoryName(xid);
        FileTxContext ctx = loadOrGetContext(xid);

        Path txDir = ctx.getTxDir();

        // Idempotency: already committed
        if (Files.exists(txDir.resolve(FLAG_COMMITTED))) {
            log.info("commit: already COMMITTED for {}", key);
            return;
        }

        try {
            // Create COMMITTING flag
            Path committingFlag = txDir.resolve(FLAG_COMMITTING);
            if (!Files.exists(committingFlag)) {
                DurabilityHelper.createFlagFile(committingFlag);
            }
            ctx.setState(TxState.COMMITTING);

            boolean anyOperationAlreadyCommitted = false;

            for (FileOperation op : ctx.getOperations()) {
                Path stagingFile = stagingPath(ctx, op);
                Path targetPath = op.getTargetPath();

                // Atomic rename is all-or-nothing: if the staging file is already
                // gone AND the target is present, a prior (possibly crashed) commit
                // attempt already moved it into place for this exact operation --
                // that pairing is definitive proof. Files.exists() alone is not
                // enough: it also returns false when it cannot determine existence
                // due to an I/O error, not just genuine absence, so treating a
                // missing staging file as "already moved" without confirming the
                // target risks silently committing an incomplete operation set. If
                // the target isn't there either, fall through to the move attempt
                // below, which fails loudly (not silently) when staging is
                // genuinely gone.
                if (!Files.exists(stagingFile) && Files.exists(targetPath)) {
                    anyOperationAlreadyCommitted = true;
                    continue;
                }

                try {
                    // Ensure parent directory exists
                    Files.createDirectories(targetPath.getParent());

                    // For REPLACE_EXISTING, backup existing target
                    if (op.getMode() == WriteMode.REPLACE_EXISTING && Files.exists(targetPath)) {
                        Path backup = backupPath(key, op);
                        Files.createDirectories(backup.getParent());
                        Files.move(targetPath, backup, StandardCopyOption.ATOMIC_MOVE);
                    }

                    // Atomic move staging → target
                    Files.move(stagingFile, targetPath, StandardCopyOption.ATOMIC_MOVE);

                    DurabilityHelper.fsyncFile(targetPath);
                    DurabilityHelper.fsyncDir(targetPath.getParent());

                    anyOperationAlreadyCommitted = true;
                } catch (IOException | UnsupportedOperationException e) {
                    if (anyOperationAlreadyCommitted) {
                        // Another operation in this SAME transaction already
                        // durably succeeded (its target now holds the new
                        // content) while this one cannot complete right now.
                        // We do not know the final outcome for certain: we have
                        // not undone this operation, so a later commit() retry
                        // could still finish it if the underlying problem
                        // clears (see README's "Higher probability of heuristic
                        // outcomes" limitation) -- that ambiguity is exactly
                        // XA_HEURHAZ, not XA_HEURMIX (which requires KNOWING
                        // that part was committed and another part was rolled
                        // back). The resource must say so, so the TM can
                        // reconcile it instead of treating this as a plain
                        // retriable failure.
                        try {
                            Path heuristicFlag = txDir.resolve(FLAG_HEURISTIC_HAZARD);
                            if (!Files.exists(heuristicFlag)) {
                                DurabilityHelper.createFlagFile(heuristicFlag);
                            }
                        } catch (IOException flagError) {
                            log.warn("Failed to record {} flag for {}", FLAG_HEURISTIC_HAZARD, key, flagError);
                        }
                        log.error("Heuristic hazard for xid {}: op {} failed after another op already committed",
                                key, op.getOpId(), e);
                        XAException xa = new XAException(XAException.XA_HEURHAZ);
                        xa.initCause(e);
                        throw xa;
                    }
                    log.error("commit failed for xid {} on op {}", key, op.getOpId(), e);
                    XAException xa = new XAException(XAException.XAER_RMERR);
                    xa.initCause(e);
                    throw xa;
                }
            }

            // Create COMMITTED flag in txDir
            DurabilityHelper.createFlagFile(txDir.resolve(FLAG_COMMITTED));
            DurabilityHelper.fsyncDir(txDir);
            ctx.setState(TxState.COMMITTED);

            // Create per-operation commit flag files
            for (FileOperation op : ctx.getOperations()) {
                Path commitFlagPath = op.getCommitFlagPath();
                Files.createDirectories(commitFlagPath.getParent());
                if (!Files.exists(commitFlagPath)) {
                    DurabilityHelper.createFlagFile(commitFlagPath);
                }
            }

            // Cleanup backups
            for (FileOperation op : ctx.getOperations()) {
                try {
                    Files.deleteIfExists(backupPath(key, op));
                } catch (IOException e) {
                    log.warn("Failed to delete backup for op {}", op.getOpId(), e);
                }
            }

        } catch (XAException e) {
            throw e;
        } catch (IOException e) {
            log.error("commit failed for xid {}", key, e);
            XAException xa = new XAException(XAException.XAER_RMERR);
            xa.initCause(e);
            throw xa;
        }
    }

    @Override
    public void rollback(Xid xid) throws XAException {
        String key = XidUtils.toDirectoryName(xid);
        FileTxContext ctx = loadOrGetContext(xid);

        Path txDir = ctx.getTxDir();

        // Idempotency: already rolled back
        if (Files.exists(txDir.resolve(FLAG_ROLLED_BACK))) {
            log.info("rollback: already ROLLED_BACK for {}", key);
            return;
        }

        try {
            // Create ROLLING_BACK flag
            Path rollingBackFlag = txDir.resolve(FLAG_ROLLING_BACK);
            if (!Files.exists(rollingBackFlag)) {
                DurabilityHelper.createFlagFile(rollingBackFlag);
            }
            ctx.setState(TxState.ROLLING_BACK);

            for (FileOperation op : ctx.getOperations()) {
                Path targetPath = op.getTargetPath();
                Path commitFlagPath = op.getCommitFlagPath();
                Path stagingFile = stagingPath(ctx, op);
                Path backup = backupPath(key, op);

                // Delete commit flag if it exists
                Files.deleteIfExists(commitFlagPath);

                if (op.getMode() == WriteMode.REPLACE_EXISTING && Files.exists(backup)) {
                    // Commit started and backed up the original — restore it
                    Files.createDirectories(targetPath.getParent());
                    Files.move(backup, targetPath, StandardCopyOption.ATOMIC_MOVE);
                } else if (op.getMode() == WriteMode.CREATE_NEW) {
                    // For CREATE_NEW: the staging file was never atomically moved to the target
                    // during rollback (commit was not reached), so delete the target if it exists
                    // (e.g., commit partially succeeded before crash).
                    Files.deleteIfExists(targetPath);
                }
                // For REPLACE_EXISTING with no backup: commit never moved the original,
                // so the target is untouched and must be left in place.

                // Delete staging file
                Files.deleteIfExists(stagingFile);
            }

            // Create ROLLED_BACK flag
            DurabilityHelper.createFlagFile(txDir.resolve(FLAG_ROLLED_BACK));
            ctx.setState(TxState.ROLLED_BACK);

            // Best-effort cleanup of backup and staging dirs (they may contain other tx files)
            tryDeleteEmpty(rm.getBackupDir());
            tryDeleteEmpty(rm.getStagingDir());

        } catch (IOException e) {
            log.error("rollback failed for xid {}", key, e);
            XAException xa = new XAException(XAException.XAER_RMERR);
            xa.initCause(e);
            throw xa;
        }
    }

    @Override
    public Xid[] recover(int flag) throws XAException {
        List<Xid> inDoubt = new ArrayList<>();
        Path txBase = rm.getTxBaseDir();

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(txBase)) {
            for (Path txDir : dirs) {
                if (!Files.isDirectory(txDir)) continue;

                boolean hasPrepared = Files.exists(txDir.resolve(FLAG_PREPARED));
                boolean hasCommitting = Files.exists(txDir.resolve(FLAG_COMMITTING));
                boolean hasCommitted = Files.exists(txDir.resolve(FLAG_COMMITTED));
                boolean hasRolledBack = Files.exists(txDir.resolve(FLAG_ROLLED_BACK));

                if ((hasPrepared || hasCommitting) && !hasCommitted && !hasRolledBack) {
                    Path metaFile = txDir.resolve(META_FILE);
                    if (!Files.exists(metaFile)) continue;
                    try {
                        TxMetadata meta = TxMetadata.loadFrom(metaFile);
                        Xid xid = meta.getXid();
                        inDoubt.add(xid);
                        // Ensure context is loaded
                        loadContextFromMeta(meta, txDir);
                    } catch (IOException e) {
                        log.warn("Failed to load metadata from {}", txDir, e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("recover() failed while scanning txBaseDir", e);
            XAException xa = new XAException(XAException.XAER_RMERR);
            xa.initCause(e);
            throw xa;
        }

        return inDoubt.toArray(new Xid[0]);
    }

    @Override
    public void forget(Xid xid) throws XAException {
        String key = XidUtils.toDirectoryName(xid);
        Path txDir = rm.getTxDir(xid);
        try {
            deleteDirectoryRecursive(txDir);
        } catch (IOException e) {
            log.warn("forget: failed to delete txDir {}", txDir, e);
        }
        contexts.remove(key);
    }

    @Override
    public boolean isSameRM(XAResource xaResource) throws XAException {
        if (!(xaResource instanceof FileXaResource other)) return false;
        return rm.getRmHome().equals(other.rm.getRmHome());
    }

    @Override
    public int getTransactionTimeout() throws XAException {
        return transactionTimeout;
    }

    @Override
    public boolean setTransactionTimeout(int seconds) throws XAException {
        this.transactionTimeout = seconds;
        return true;
    }

    // -------------------------------------------------------------------------
    // Package-visible helpers used by FileXaSession
    // -------------------------------------------------------------------------

    Map<String, FileTxContext> getContexts() {
        return contexts;
    }

    FileResourceManager getResourceManager() {
        return rm;
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private FileTxContext getContext(Xid xid) throws XAException {
        String key = XidUtils.toDirectoryName(xid);
        FileTxContext ctx = contexts.get(key);
        if (ctx == null) {
            throw new XAException(XAException.XAER_NOTA);
        }
        return ctx;
    }

    private FileTxContext loadOrGetContext(Xid xid) throws XAException {
        String key = XidUtils.toDirectoryName(xid);
        FileTxContext ctx = contexts.get(key);
        if (ctx != null) return ctx;

        // Try to load from disk (recovery path)
        Path txDir = rm.getTxDir(xid);
        Path metaFile = txDir.resolve(META_FILE);
        if (!Files.exists(metaFile)) {
            throw new XAException(XAException.XAER_NOTA);
        }
        try {
            TxMetadata meta = TxMetadata.loadFrom(metaFile);
            return loadContextFromMeta(meta, txDir);
        } catch (IOException e) {
            log.error("Failed to load context from disk for xid {}", key, e);
            XAException xa = new XAException(XAException.XAER_RMERR);
            xa.initCause(e);
            throw xa;
        }
    }

    private FileTxContext loadContextFromMeta(TxMetadata meta, Path txDir) {
        String key = meta.getXidDirName();
        // If already loaded, return existing
        if (contexts.containsKey(key)) return contexts.get(key);

        FileTxContext ctx = new FileTxContext(meta.getXid(), txDir);

        for (TxMetadata.OpMetadata opMeta : meta.getOperations()) {
            // During recovery the in-memory byte[] content is not needed;
            // the staging file persisted on disk is the authoritative source.
            FileOperation op = new FileOperation(
                    opMeta.getOpId(),
                    Path.of(opMeta.getTargetPath()),
                    Path.of(opMeta.getCommitFlagPath()),
                    WriteMode.valueOf(opMeta.getMode()),
                    null
            );
            ctx.addOperation(op);
        }

        // Determine state from flag files
        if (Files.exists(txDir.resolve(FLAG_COMMITTED))) {
            ctx.setState(TxState.COMMITTED);
        } else if (Files.exists(txDir.resolve(FLAG_COMMITTING))) {
            ctx.setState(TxState.COMMITTING);
        } else if (Files.exists(txDir.resolve(FLAG_PREPARED))) {
            ctx.setState(TxState.PREPARED);
        } else if (Files.exists(txDir.resolve(FLAG_ROLLED_BACK))) {
            ctx.setState(TxState.ROLLED_BACK);
        } else if (Files.exists(txDir.resolve(FLAG_ROLLING_BACK))) {
            ctx.setState(TxState.ROLLING_BACK);
        }

        contexts.put(key, ctx);
        return ctx;
    }

    private Path stagingPath(FileTxContext ctx, FileOperation op) {
        String xidKey = XidUtils.toDirectoryName(ctx.getXid());
        return rm.getStagingDir().resolve(xidKey + "-" + op.getOpId() + ".tmp");
    }

    private Path backupPath(String xidKey, FileOperation op) {
        return rm.getBackupDir().resolve(xidKey + "-" + op.getOpId() + ".bak");
    }

    private void tryDeleteEmpty(Path dir) {
        try {
            if (Files.isDirectory(dir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                    if (!ds.iterator().hasNext()) {
                        Files.deleteIfExists(dir);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Best-effort delete of {} failed: {}", dir, e.getMessage());
        }
    }

    private void deleteDirectoryRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path entry : ds) {
                if (Files.isDirectory(entry)) {
                    deleteDirectoryRecursive(entry);
                } else {
                    Files.deleteIfExists(entry);
                }
            }
        }
        Files.deleteIfExists(dir);
    }
}
