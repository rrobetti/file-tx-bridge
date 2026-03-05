package com.example.filetxbridge;

import com.example.filetxbridge.core.WriteMode;
import com.example.filetxbridge.recovery.RecoveryManager;
import com.example.filetxbridge.xa.FileXaResource;
import com.example.filetxbridge.xa.FileXaSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.Xid;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RecoveryManagerTest {

    @TempDir
    Path tempDir;

    static final class TestXid implements Xid {
        private final int id;

        TestXid(int id) { this.id = id; }

        @Override public int getFormatId() { return 1; }
        @Override public byte[] getGlobalTransactionId() { return ("global-rm-" + id).getBytes(); }
        @Override public byte[] getBranchQualifier() { return ("branch-rm-" + id).getBytes(); }
    }

    @Test
    void testScanInDoubtAfterPrepare() throws Exception {
        Path rmHome = tempDir.resolve("rm-recovery1");
        Files.createDirectories(rmHome);

        FileResourceManager rm1 = new FileResourceManager(rmHome);
        FileXaSession session = new FileXaSession(rm1);
        FileXaResource xaResource1 = session.getXaResource();

        Xid xid = new TestXid(1);
        Path target = tempDir.resolve("recovery-scan.txt");
        Path commitFlag = tempDir.resolve("recovery-scan.txt.committed");

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "data".getBytes(), WriteMode.CREATE_NEW);
        session.end();
        xaResource1.prepare(xid);

        // New RM and RecoveryManager simulating restart
        FileResourceManager rm2 = new FileResourceManager(rmHome);
        FileXaResource xaResource2 = new FileXaResource(rm2);
        RecoveryManager recoveryManager = new RecoveryManager(rm2, xaResource2);

        List<Xid> inDoubt = recoveryManager.scanInDoubt();
        assertEquals(1, inDoubt.size(), "Should find 1 in-doubt transaction after prepare");
    }

    @Test
    void testScanInDoubtEmptyAfterCommit() throws Exception {
        Path rmHome = tempDir.resolve("rm-recovery2");
        Files.createDirectories(rmHome);

        FileResourceManager rm1 = new FileResourceManager(rmHome);
        FileXaSession session = new FileXaSession(rm1);
        FileXaResource xaResource1 = session.getXaResource();

        Xid xid = new TestXid(2);
        Path target = tempDir.resolve("recovery-committed.txt");
        Path commitFlag = tempDir.resolve("recovery-committed.txt.committed");

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "data".getBytes(), WriteMode.CREATE_NEW);
        session.end();
        xaResource1.prepare(xid);
        xaResource1.commit(xid, false);

        // New RM and RecoveryManager simulating restart
        FileResourceManager rm2 = new FileResourceManager(rmHome);
        FileXaResource xaResource2 = new FileXaResource(rm2);
        RecoveryManager recoveryManager = new RecoveryManager(rm2, xaResource2);

        List<Xid> inDoubt = recoveryManager.scanInDoubt();
        assertTrue(inDoubt.isEmpty(), "No in-doubt transactions after commit");
    }
}
