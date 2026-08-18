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
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
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

        if (!Files.exists(txDir)) {
            // The tx directory is gone -- e.g. a concurrent abandoned-transaction
            // sweep (RecoveryManager#cleanupAbandonedTransactions) raced with this
            // prepare() call, or external interference removed it (see README's
            // "External interference" limitation). Either way, this branch can
            // never be committed. XA_RBROLLBACK is a promise, not just a vote: it
            // tells the TM this branch has ALREADY been rolled back, so it will
            // never call rollback() on it. That promise is only true once we
            // actually clean up this operation's staging files ourselves first --
            // targets were never created (commit() never ran), but leftover staging
            // files would mean we hadn't really achieved a rolled-back state.
            log.error("prepare: tx directory missing for xid {}, cleaning up and reporting as already rolled back",
                    XidUtils.toDirectoryName(xid));
            deleteStagingFilesBestEffort(ctx);
            throw new XAException(XAException.XA_RBROLLBACK);
        }

        try {
            // Ensure target/commit-flag parent directories exist, confirm the
            // staging directory and target directory are on the same
            // filesystem (Files.move's ATOMIC_MOVE requires that), and -- for
            // REPLACE_EXISTING -- take a safety copy of any pre-existing
            // target. All of this used to happen in commit(), where a failure
            // meant a heuristic outcome once other operations in the same
            // transaction had already committed. Doing it here instead means
            // a failure is just a clean vote-no (XAER_RMERR below), and
            // commit() is reduced to a single atomic move per operation.
            for (FileOperation op : ctx.getOperations()) {
                Path targetDir = op.getTargetPath().getParent();
                op.setTargetDirCreationRoot(createDirectoriesTrackingRoot(targetDir));

                Path commitFlagDir = op.getCommitFlagPath().getParent();
                op.setCommitFlagDirCreationRoot(createDirectoriesTrackingRoot(commitFlagDir));

                if (!Files.getFileStore(rm.getStagingDir()).equals(Files.getFileStore(targetDir))) {
                    throw new IOException("staging directory " + rm.getStagingDir()
                            + " and target directory " + targetDir + " are on different "
                            + "filesystems -- an atomic move between them is not possible");
                }

                if (op.getMode() == WriteMode.REPLACE_EXISTING && Files.exists(op.getTargetPath())) {
                    Path backup = backupPath(XidUtils.toDirectoryName(xid), op);
                    Files.createDirectories(backup.getParent());
                    Files.copy(op.getTargetPath(), backup,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    DurabilityHelper.fsyncFile(backup);
                    DurabilityHelper.fsyncDir(backup.getParent());
                }
            }

            // Build and persist metadata
            List<TxMetadata.OpMetadata> opMetas = new ArrayList<>();
            for (FileOperation op : ctx.getOperations()) {
                opMetas.add(new TxMetadata.OpMetadata(
                        op.getOpId(),
                        op.getTargetPath().toAbsolutePath().toString(),
                        stagingPath(ctx, op).toAbsolutePath().toString(),
                        op.getCommitFlagPath().toAbsolutePath().toString(),
                        op.getMode().name(),
                        op.getTargetDirCreationRoot() == null ? null
                                : op.getTargetDirCreationRoot().toAbsolutePath().toString(),
                        op.getCommitFlagDirCreationRoot() == null ? null
                                : op.getCommitFlagDirCreationRoot().toAbsolutePath().toString()
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
        } catch (NoSuchFileException e) {
            // The tx directory disappeared mid-prepare (same race/interference as
            // above, just caught partway through instead of at the top). Same
            // reasoning: clean up first, so "already rolled back" is actually true.
            log.error("prepare: tx directory disappeared mid-prepare for xid {}, cleaning up and reporting as already rolled back",
                    XidUtils.toDirectoryName(xid), e);
            deleteStagingFilesBestEffort(ctx);
            XAException xa = new XAException(XAException.XA_RBROLLBACK);
            xa.initCause(e);
            throw xa;
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
                    // Self-healing hedge only: the parent directory, the same-
                    // filesystem guarantee, and (for REPLACE_EXISTING) the backup
                    // copy of any pre-existing target were already ensured during
                    // prepare(). This createDirectories() only does real work if
                    // something external removed the directory again in between.
                    Files.createDirectories(targetPath.getParent());

                    // Atomic move staging → target. REPLACE_EXISTING is only added
                    // for WriteMode.REPLACE_EXISTING -- for CREATE_NEW we still want
                    // the move to fail if a file unexpectedly showed up at the
                    // target in the meantime, rather than silently overwrite it.
                    if (op.getMode() == WriteMode.REPLACE_EXISTING) {
                        Files.move(stagingFile, targetPath,
                                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } else {
                        Files.move(stagingFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
                    }

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

                // A backup here is only ever prepare()'s safety COPY of a
                // pre-existing target (see prepare()). The original target is
                // never touched before commit() runs, and commit() is never
                // invoked once rollback() is (a resource that voted XA_OK is
                // only ever asked to commit or forget, never rollback -- see
                // prepare()'s XA_RBROLLBACK path and commit()'s heuristic
                // handling for the two cases where that vote is withdrawn
                // before commit()). So the target needs no restoring here;
                // just discard the now-unneeded copy.
                Files.deleteIfExists(backup);

                if (op.getMode() == WriteMode.CREATE_NEW) {
                    // Defensive: under the reasoning above this should never
                    // find anything, but costs nothing to guard against a
                    // target that appeared at this path outside this
                    // transaction's own commit() path.
                    Files.deleteIfExists(targetPath);
                }

                // Delete staging file
                Files.deleteIfExists(stagingFile);

                // Remove any parent directories prepare() created for this
                // operation's target/commit-flag paths, if still empty --
                // never touches a directory that already existed before
                // prepare() (see createDirectoriesTrackingRoot()).
                deleteCreatedDirectoryChain(targetPath.getParent(), op.getTargetDirCreationRoot());
                deleteCreatedDirectoryChain(commitFlagPath.getParent(), op.getCommitFlagDirCreationRoot());
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

    /**
     * Whether this exact resource instance currently tracks the given tx directory
     * key ({@link com.example.filetxbridge.util.XidUtils#toDirectoryName(Xid)}) in
     * memory. Used by recovery-adjacent tooling (e.g. an age-based abandoned-
     * transaction sweep) to tell apart "this JVM never started this transaction"
     * (safe to reason about from on-disk age alone) from "this JVM is still
     * actively holding it" (never safe: prepare() is not barred by a transaction
     * manager's timeout the way commit() is, so a legitimately slow prepare() could
     * still land on it at any moment).
     */
    public boolean hasInMemoryContext(String xidKey) {
        return contexts.containsKey(xidKey);
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
            if (opMeta.getTargetDirCreationRoot() != null) {
                op.setTargetDirCreationRoot(Path.of(opMeta.getTargetDirCreationRoot()));
            }
            if (opMeta.getCommitFlagDirCreationRoot() != null) {
                op.setCommitFlagDirCreationRoot(Path.of(opMeta.getCommitFlagDirCreationRoot()));
            }
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

    /**
     * Deletes every operation's staging file for this context, best-effort. Used
     * when {@link #prepare(Xid)} discovers its own tx directory is already gone: it
     * still has the operations in memory (meta.properties was never durably
     * written), so it can and must clean these up itself before it can honestly
     * report XA_RBROLLBACK -- that code promises the branch is ALREADY rolled
     * back, and target files aside (never created, since commit() never ran),
     * leftover staging files are the one thing that promise would otherwise break.
     */
    private void deleteStagingFilesBestEffort(FileTxContext ctx) {
        for (FileOperation op : ctx.getOperations()) {
            try {
                Files.deleteIfExists(stagingPath(ctx, op));
            } catch (IOException e) {
                log.warn("Failed to delete staging file for op {} during prepare-failure cleanup", op.getOpId(), e);
            }
        }
    }

    private Path backupPath(String xidKey, FileOperation op) {
        return rm.getBackupDir().resolve(xidKey + "-" + op.getOpId() + ".bak");
    }

    /**
     * Creates {@code dir} and any missing ancestors (like {@link Files#createDirectories}),
     * and returns the topmost ancestor that did not already exist -- {@code null} if
     * {@code dir} already existed. The returned "creation root" lets a later
     * {@link #rollback} undo exactly what was created here, and nothing that
     * pre-existed it.
     */
    private Path createDirectoriesTrackingRoot(Path dir) throws IOException {
        Path missingRoot = null;
        Path p = dir;
        while (p != null && !Files.exists(p)) {
            missingRoot = p;
            p = p.getParent();
        }
        Files.createDirectories(dir);
        return missingRoot;
    }

    /**
     * Best-effort: deletes {@code leaf} and any of its ancestors up to and including
     * {@code creationRoot} (as recorded by {@link #createDirectoriesTrackingRoot}),
     * stopping as soon as one is non-empty (something else has used it since) or
     * already gone. Never touches anything above {@code creationRoot} -- that
     * ancestor already existed before prepare() created the rest of the chain, so
     * it is not this transaction's to remove. No-op if {@code creationRoot} is null
     * (the directory already existed at prepare()-time).
     */
    private void deleteCreatedDirectoryChain(Path leaf, Path creationRoot) {
        if (creationRoot == null) return;
        Path dir = leaf;
        while (dir != null) {
            try {
                Files.delete(dir);
            } catch (NoSuchFileException e) {
                // already gone -- fine
            } catch (DirectoryNotEmptyException e) {
                return; // something else is using it now; stop, go no further up
            } catch (IOException e) {
                log.warn("Failed to remove created directory {} during rollback cleanup", dir, e);
                return;
            }
            if (dir.equals(creationRoot)) return;
            dir = dir.getParent();
        }
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
