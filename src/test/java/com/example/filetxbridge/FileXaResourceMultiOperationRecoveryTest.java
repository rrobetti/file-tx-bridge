package com.example.filetxbridge;

import com.example.filetxbridge.core.WriteMode;
import com.example.filetxbridge.util.XidUtils;
import com.example.filetxbridge.xa.FileXaResource;
import com.example.filetxbridge.xa.FileXaSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FileXaResource#commit(Xid, boolean) moves each operation's staged file into place
 * one at a time in a loop, and only writes the COMMITTED flag after the entire loop
 * finishes. All existing tests (FileXaResourceTest, RecoveryManagerTest) use exactly
 * one operation per transaction, so they cannot see what happens when a crash lands
 * between two operations' moves within a single transaction.
 *
 * <p>This test reproduces that crash window directly: it drives a real two-operation
 * transaction through prepare(), then applies -- by hand, via the same staging-path
 * convention FileXaSession/FileXaResource use internally -- exactly what commit()
 * would have done for the first operation, and stops there (as if the process died
 * before reaching the second operation). A fresh FileXaResource against the same
 * rmHome then must be able to recover and finish the transaction.
 */
class FileXaResourceMultiOperationRecoveryTest {

    @TempDir
    Path rmHome;

    @Test
    void recoveryCompletesRemainingOperationAfterCrashBetweenTwoCommitMoves() throws Exception {
        FileResourceManager rm = new FileResourceManager(rmHome);
        FileXaSession session = new FileXaSession(rm);
        Xid xid = new FileXaResourceTest.TestXid(100);

        Path targetA = rmHome.resolve("a.txt");
        Path targetB = rmHome.resolve("b.txt");
        byte[] contentA = "content-a".getBytes(StandardCharsets.UTF_8);
        byte[] contentB = "content-b".getBytes(StandardCharsets.UTF_8);

        session.begin(xid);
        session.addCreateFile(targetA, null, contentA, WriteMode.CREATE_NEW);
        session.addCreateFile(targetB, null, contentB, WriteMode.CREATE_NEW);
        session.end();
        session.getXaResource().prepare(xid);

        // Simulate a crash midway through commit(): apply exactly what commit()
        // would have done for operation A (op-0) -- move its staged file into
        // place -- then stop, as if the process died before reaching operation B
        // (op-1). Same staging-path convention FileXaResource itself uses.
        String xidKey = XidUtils.toDirectoryName(xid);
        Path stagingA = rm.getStagingDir().resolve(xidKey + "-op-0.tmp");
        Files.move(stagingA, targetA, StandardCopyOption.ATOMIC_MOVE);
        Files.createFile(rm.getTxDir(xid).resolve(FileXaResource.FLAG_COMMITTING));

        // A fresh FileXaResource against the same rmHome simulates a JVM restart.
        FileXaResource recovered = new FileXaResource(rm);
        Xid[] inDoubt = recovered.recover(XAResource.TMSTARTRSCAN);
        assertEquals(1, inDoubt.length, "expected exactly one in-doubt Xid");

        recovered.commit(inDoubt[0], false);

        assertArrayEquals(contentA, Files.readAllBytes(targetA));
        assertArrayEquals(contentB, Files.readAllBytes(targetB));
        assertTrue(Files.exists(rm.getTxDir(xid).resolve(FileXaResource.FLAG_COMMITTED)),
                "transaction must be fully COMMITTED, not stuck in-doubt forever");
    }
}
