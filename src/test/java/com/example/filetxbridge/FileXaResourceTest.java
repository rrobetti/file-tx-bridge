package com.example.filetxbridge;

import com.example.filetxbridge.core.WriteMode;
import com.example.filetxbridge.xa.FileXaResource;
import com.example.filetxbridge.xa.FileXaSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileXaResourceTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Test helpers
    // -------------------------------------------------------------------------

    static final class TestXid implements Xid {
        private final int id;

        TestXid(int id) {
            this.id = id;
        }

        @Override
        public int getFormatId() { return 1; }

        @Override
        public byte[] getGlobalTransactionId() {
            return ("global-" + id).getBytes();
        }

        @Override
        public byte[] getBranchQualifier() {
            return ("branch-" + id).getBytes();
        }
    }

    private FileResourceManager newRm(Path home) throws Exception {
        return new FileResourceManager(home);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    @Test
    void testCommitCreatesTargetAndFlagFile() throws Exception {
        Path rmHome = tempDir.resolve("rm1");
        Files.createDirectories(rmHome);

        FileResourceManager rm = newRm(rmHome);
        FileXaSession session = new FileXaSession(rm);
        FileXaResource xaResource = session.getXaResource();

        Xid xid = new TestXid(1);
        Path target = tempDir.resolve("output.txt");
        Path commitFlag = tempDir.resolve("output.txt.committed");

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "hello".getBytes(), WriteMode.CREATE_NEW);
        session.end();

        xaResource.prepare(xid);
        xaResource.commit(xid, false);

        assertTrue(Files.exists(target), "Target file must exist after commit");
        assertArrayEquals("hello".getBytes(), Files.readAllBytes(target));
        assertTrue(Files.exists(commitFlag), "Commit flag file must exist after commit");
    }

    @Test
    void testRollbackDeletesTargetAndFlagFile() throws Exception {
        Path rmHome = tempDir.resolve("rm2");
        Files.createDirectories(rmHome);

        FileResourceManager rm = newRm(rmHome);
        FileXaSession session = new FileXaSession(rm);
        FileXaResource xaResource = session.getXaResource();

        Xid xid = new TestXid(2);
        Path target = tempDir.resolve("rollback-output.txt");
        Path commitFlag = tempDir.resolve("rollback-output.txt.committed");

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "hello".getBytes(), WriteMode.CREATE_NEW);
        session.end();

        xaResource.prepare(xid);
        xaResource.rollback(xid);

        assertFalse(Files.exists(target), "Target file must NOT exist after rollback");
        assertFalse(Files.exists(commitFlag), "Commit flag file must NOT exist after rollback");
    }

    @Test
    void testIdempotentCommit() throws Exception {
        Path rmHome = tempDir.resolve("rm3");
        Files.createDirectories(rmHome);

        FileResourceManager rm = newRm(rmHome);
        FileXaSession session = new FileXaSession(rm);
        FileXaResource xaResource = session.getXaResource();

        Xid xid = new TestXid(3);
        Path target = tempDir.resolve("idempotent-output.txt");
        Path commitFlag = tempDir.resolve("idempotent-output.txt.committed");

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "hello".getBytes(), WriteMode.CREATE_NEW);
        session.end();

        xaResource.prepare(xid);
        xaResource.commit(xid, false);

        // Second commit should not throw
        assertDoesNotThrow(() -> xaResource.commit(xid, false));
        assertTrue(Files.exists(target));
    }

    @Test
    void testIdempotentRollback() throws Exception {
        Path rmHome = tempDir.resolve("rm4");
        Files.createDirectories(rmHome);

        FileResourceManager rm = newRm(rmHome);
        FileXaSession session = new FileXaSession(rm);
        FileXaResource xaResource = session.getXaResource();

        Xid xid = new TestXid(4);
        Path target = tempDir.resolve("idempotent-rb-output.txt");
        Path commitFlag = tempDir.resolve("idempotent-rb-output.txt.committed");

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "hello".getBytes(), WriteMode.CREATE_NEW);
        session.end();

        xaResource.prepare(xid);
        xaResource.rollback(xid);

        // Second rollback should not throw
        assertDoesNotThrow(() -> xaResource.rollback(xid));
        assertFalse(Files.exists(target));
    }

    @Test
    void testCrashRecoveryAfterPrepare() throws Exception {
        Path rmHome = tempDir.resolve("rm5");
        Files.createDirectories(rmHome);

        // First RM: begin, add file, end, prepare (simulate crash after prepare)
        FileResourceManager rm1 = newRm(rmHome);
        FileXaSession session1 = new FileXaSession(rm1);
        FileXaResource xaResource1 = session1.getXaResource();

        Xid xid = new TestXid(5);
        Path target = tempDir.resolve("crash-recovery.txt");
        Path commitFlag = tempDir.resolve("crash-recovery.txt.committed");

        session1.begin(xid);
        session1.addCreateFile(target, commitFlag, "recovered".getBytes(), WriteMode.CREATE_NEW);
        session1.end();
        xaResource1.prepare(xid);

        // Simulate restart: create new RM and XAResource with same rmHome
        FileResourceManager rm2 = newRm(rmHome);
        FileXaResource xaResource2 = new FileXaResource(rm2);

        Xid[] inDoubt = xaResource2.recover(XAResource.TMSTARTRSCAN);

        assertNotNull(inDoubt);
        assertEquals(1, inDoubt.length, "Should find 1 in-doubt transaction");

        // Commit the recovered transaction
        xaResource2.commit(inDoubt[0], false);

        assertTrue(Files.exists(target), "Target file must exist after recovery commit");
        assertArrayEquals("recovered".getBytes(), Files.readAllBytes(target));
    }

    @Test
    void testReplaceExistingRollbackRestoresOldContent() throws Exception {
        Path rmHome = tempDir.resolve("rm6");
        Files.createDirectories(rmHome);

        FileResourceManager rm = newRm(rmHome);
        FileXaSession session = new FileXaSession(rm);
        FileXaResource xaResource = session.getXaResource();

        Xid xid = new TestXid(6);
        Path target = tempDir.resolve("replace-rollback.txt");
        Path commitFlag = tempDir.resolve("replace-rollback.txt.committed");

        // Pre-existing file with "old content"
        Files.write(target, "old content".getBytes());

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "new content".getBytes(), WriteMode.REPLACE_EXISTING);
        session.end();

        xaResource.prepare(xid);
        xaResource.rollback(xid);

        assertTrue(Files.exists(target), "Target must still exist after rollback (restored)");
        assertArrayEquals("old content".getBytes(), Files.readAllBytes(target),
                "Old content must be restored after rollback");
    }

    @Test
    void testReplaceExistingCommitReplacesContent() throws Exception {
        Path rmHome = tempDir.resolve("rm7");
        Files.createDirectories(rmHome);

        FileResourceManager rm = newRm(rmHome);
        FileXaSession session = new FileXaSession(rm);
        FileXaResource xaResource = session.getXaResource();

        Xid xid = new TestXid(7);
        Path target = tempDir.resolve("replace-commit.txt");
        Path commitFlag = tempDir.resolve("replace-commit.txt.committed");

        // Pre-existing file with "old content"
        Files.write(target, "old content".getBytes());

        session.begin(xid);
        session.addCreateFile(target, commitFlag, "new content".getBytes(), WriteMode.REPLACE_EXISTING);
        session.end();

        xaResource.prepare(xid);
        xaResource.commit(xid, false);

        assertTrue(Files.exists(target), "Target must exist after commit");
        assertArrayEquals("new content".getBytes(), Files.readAllBytes(target),
                "Target must contain new content after commit");
    }
}
