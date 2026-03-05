package com.example.filetxbridge;

import com.example.filetxbridge.lock.PathLockManager;
import com.example.filetxbridge.util.XidUtils;

import javax.transaction.xa.Xid;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FileResourceManager {

    private final Path rmHome;
    private final Path stagingDir;
    private final Path txBaseDir;
    private final Path backupDir;
    private final PathLockManager lockManager;

    public FileResourceManager(Path rmHome) throws IOException {
        this.rmHome = rmHome.toAbsolutePath().normalize();
        this.stagingDir = this.rmHome.resolve("staging");
        this.txBaseDir = this.rmHome.resolve("tx");
        this.backupDir = this.rmHome.resolve("backup");

        Files.createDirectories(stagingDir);
        Files.createDirectories(txBaseDir);
        Files.createDirectories(backupDir);

        Path lockDir = this.rmHome.resolve("locks");
        Files.createDirectories(lockDir);
        this.lockManager = new PathLockManager(lockDir, 5000);
    }

    public Path getRmHome() {
        return rmHome;
    }

    public Path getStagingDir() {
        return stagingDir;
    }

    public Path getTxBaseDir() {
        return txBaseDir;
    }

    public Path getBackupDir() {
        return backupDir;
    }

    public PathLockManager getLockManager() {
        return lockManager;
    }

    public Path getTxDir(Xid xid) {
        return txBaseDir.resolve(XidUtils.toDirectoryName(xid));
    }
}
