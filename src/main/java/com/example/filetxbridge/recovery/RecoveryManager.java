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
import java.util.ArrayList;
import java.util.List;

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
}
