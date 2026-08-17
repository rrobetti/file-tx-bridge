package com.example.filetxbridge.recovery;

import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.xa.FileXaResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecoveryManager {

    private static final Logger log = LoggerFactory.getLogger(RecoveryManager.class);

    private final FileResourceManager rm;
    private final FileXaResource xaResource;

    public RecoveryManager(FileResourceManager rm, FileXaResource xaResource) {
        this.rm = rm;
        this.xaResource = xaResource;
    }

    /**
     * Scans the txBaseDir for transactions that are in-doubt:
     * have PREPARED or COMMITTING flag but not COMMITTED or ROLLED_BACK.
     */
    public List<Xid> scanInDoubt() {
        List<Xid> result = new ArrayList<>();
        Path txBase = rm.getTxBaseDir();

        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(txBase)) {
            for (Path txDir : dirs) {
                if (!Files.isDirectory(txDir)) continue;

                boolean hasPrepared = Files.exists(txDir.resolve(FileXaResource.FLAG_PREPARED));
                boolean hasCommitting = Files.exists(txDir.resolve(FileXaResource.FLAG_COMMITTING));
                boolean hasCommitted = Files.exists(txDir.resolve(FileXaResource.FLAG_COMMITTED));
                boolean hasRolledBack = Files.exists(txDir.resolve(FileXaResource.FLAG_ROLLED_BACK));

                if ((hasPrepared || hasCommitting) && !hasCommitted && !hasRolledBack) {
                    Path metaFile = txDir.resolve(FileXaResource.META_FILE);
                    if (!Files.exists(metaFile)) continue;
                    try {
                        TxMetadata meta = TxMetadata.loadFrom(metaFile);
                        result.add(meta.getXid());
                    } catch (IOException e) {
                        log.warn("scanInDoubt: failed to load metadata from {}", txDir, e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("scanInDoubt: error scanning txBaseDir", e);
        }

        return result;
    }

    public void recoverCommit(Xid xid) throws XAException {
        xaResource.commit(xid, false);
    }

    public void recoverRollback(Xid xid) throws XAException {
        xaResource.rollback(xid);
    }

    public Xid[] recover(int flag) throws XAException {
        return xaResource.recover(flag);
    }

    public void performStartupRecovery() {
        List<Xid> inDoubt = scanInDoubt();
        if (inDoubt.isEmpty()) {
            log.info("startup recovery: no in-doubt transactions found");
        } else {
            log.warn("startup recovery: found {} in-doubt transaction(s)", inDoubt.size());
            for (Xid xid : inDoubt) {
                log.warn("  in-doubt xid: formatId={}", xid.getFormatId());
            }
        }
    }

    private static final Pattern STAGING_FILE_XID_KEY = Pattern.compile("^(.*)-op-\\d+\\.tmp$");

    /**
     * Deletes abandoned transaction artifacts: tx directories that never reached
     * PREPARED/COMMITTING/COMMITTED/ROLLED_BACK, plus their staging files, once both
     * are older than {@code maxAge}.
     *
     * <p>{@code recover()} only ever surfaces PREPARED/COMMITTING transactions
     * (correctly, per the XA spec -- a resource must not report branches it never
     * voted YES on). A transaction that crashes before {@code prepare()} is ever
     * called leaves a tx directory and staging file(s) behind that neither this
     * resource's own {@code recover()} nor a transaction manager's reconciliation
     * (which only ever asks about what {@code recover()} returns) can discover or
     * clean up. Since the resource never returned {@code XA_OK} from prepare() for
     * these, it never made an irrevocable commitment, so it is free to unilaterally
     * give up on a sufficiently old, still-pre-prepare transaction without
     * violating the XA contract.
     *
     * <p>This is a best-effort, age-based heuristic, not something driven by the XA
     * protocol -- it is not wired into {@link #performStartupRecovery()} or any
     * automatic hook. Call it explicitly (e.g. on a schedule) with a
     * {@code maxAge} comfortably longer than your application's own transaction
     * timeout, or a slow-but-legitimate in-flight transaction could be cleaned up
     * while it is still active.
     *
     * <p><b>On-disk age alone is not sufficient.</b> A transaction manager's
     * timeout bars <em>commit()</em>, not <em>prepare()</em> -- prepare() can still
     * land late (e.g. after a network-delayed 2PC round involving other, remote
     * resources), so an unflagged tx directory that merely looks old on disk is not
     * proof of abandonment on its own. It only is if this exact {@code
     * FileXaResource} instance also has no in-memory record of it ({@link
     * FileXaResource#hasInMemoryContext}) -- which only happens once this JVM
     * never started that transaction in the first place (typically: it is a fresh
     * instance after a restart). Within a still-live JVM that does hold an
     * in-memory context for a xid, prepare() could still be called on it at any
     * moment no matter how old it looks on disk, so it is never swept.
     */
    public void cleanupAbandonedTransactions(Duration maxAge) {
        Instant cutoff = Instant.now().minus(maxAge);
        cleanupAbandonedTxDirectories(cutoff);
        cleanupOrphanedStagingFiles();
    }

    private void cleanupAbandonedTxDirectories(Instant cutoff) {
        Path txBase = rm.getTxBaseDir();
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(txBase)) {
            for (Path txDir : dirs) {
                if (!Files.isDirectory(txDir)) continue;
                if (hasAnyRecoveryRelevantFlag(txDir)) continue;
                if (xaResource.hasInMemoryContext(txDir.getFileName().toString())) continue;

                try {
                    Instant lastModified = Files.getLastModifiedTime(txDir).toInstant();
                    if (lastModified.isBefore(cutoff)) {
                        deleteDirectoryRecursive(txDir);
                        log.info("cleanupAbandonedTransactions: removed abandoned tx directory {}", txDir);
                    }
                } catch (IOException e) {
                    log.warn("cleanupAbandonedTransactions: failed to inspect/remove {}", txDir, e);
                }
            }
        } catch (IOException e) {
            log.error("cleanupAbandonedTransactions: error scanning txBaseDir", e);
        }
    }

    private boolean hasAnyRecoveryRelevantFlag(Path txDir) {
        return Files.exists(txDir.resolve(FileXaResource.FLAG_PREPARED))
                || Files.exists(txDir.resolve(FileXaResource.FLAG_COMMITTING))
                || Files.exists(txDir.resolve(FileXaResource.FLAG_COMMITTED))
                || Files.exists(txDir.resolve(FileXaResource.FLAG_ROLLED_BACK));
    }

    /**
     * Removes staging files whose owning tx directory no longer exists -- either it
     * never existed for a genuinely orphaned staging file, or {@link
     * #cleanupAbandonedTxDirectories} just removed it in this same sweep. A staging
     * file whose tx directory still exists is left alone: it may still belong to a
     * legitimately prepared (or not-yet-old-enough-to-sweep) transaction.
     */
    private void cleanupOrphanedStagingFiles() {
        Path stagingDir = rm.getStagingDir();
        Path txBase = rm.getTxBaseDir();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(stagingDir, "*.tmp")) {
            for (Path stagingFile : files) {
                Matcher matcher = STAGING_FILE_XID_KEY.matcher(stagingFile.getFileName().toString());
                if (!matcher.matches()) continue;
                String xidKey = matcher.group(1);
                if (!Files.exists(txBase.resolve(xidKey))) {
                    try {
                        Files.deleteIfExists(stagingFile);
                        log.info("cleanupAbandonedTransactions: removed orphaned staging file {}", stagingFile);
                    } catch (IOException e) {
                        log.warn("cleanupAbandonedTransactions: failed to remove staging file {}", stagingFile, e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("cleanupAbandonedTransactions: error scanning stagingDir", e);
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
