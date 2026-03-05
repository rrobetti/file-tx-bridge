package com.example.filetxbridge.lock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PathLockManager {

    private static final Logger log = LoggerFactory.getLogger(PathLockManager.class);

    private final Path lockDir;
    private final long timeoutMs;

    private static final class LockEntry {
        final FileChannel channel;
        final FileLock fileLock;
        final Path lockFile;

        LockEntry(FileChannel channel, FileLock fileLock, Path lockFile) {
            this.channel = channel;
            this.fileLock = fileLock;
            this.lockFile = lockFile;
        }
    }

    private final Map<String, LockEntry> heldLocks = new ConcurrentHashMap<>();

    public PathLockManager(Path lockDir, long timeoutMs) {
        this.lockDir = lockDir;
        this.timeoutMs = timeoutMs;
    }

    public void lock(Path targetPath) throws IOException {
        String key = encodePath(targetPath);
        Path lockFile = lockDir.resolve(key + ".lock");

        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            FileChannel channel = FileChannel.open(lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
            try {
                FileLock fileLock = channel.tryLock();
                if (fileLock != null) {
                    heldLocks.put(key, new LockEntry(channel, fileLock, lockFile));
                    return;
                }
                channel.close();
            } catch (Exception e) {
                try { channel.close(); } catch (Exception ignored) {}
                throw new IOException("Failed to acquire lock for " + targetPath, e);
            }

            if (System.currentTimeMillis() >= deadline) {
                throw new IOException("Timeout acquiring lock for " + targetPath
                        + " after " + timeoutMs + "ms");
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for lock on " + targetPath, ie);
            }
        }
    }

    public void unlock(Path targetPath) {
        String key = encodePath(targetPath);
        LockEntry entry = heldLocks.remove(key);
        if (entry == null) {
            log.debug("No lock held for {}", targetPath);
            return;
        }
        try {
            entry.fileLock.release();
        } catch (Exception e) {
            log.warn("Failed to release FileLock for {}", targetPath, e);
        }
        try {
            entry.channel.close();
        } catch (Exception e) {
            log.warn("Failed to close lock channel for {}", targetPath, e);
        }
        try {
            java.nio.file.Files.deleteIfExists(entry.lockFile);
        } catch (Exception e) {
            log.debug("Failed to delete lock file {}", entry.lockFile, e);
        }
    }

    private String encodePath(Path path) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(path.toAbsolutePath().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
