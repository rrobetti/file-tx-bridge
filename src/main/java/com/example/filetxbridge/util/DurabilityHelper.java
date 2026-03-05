package com.example.filetxbridge.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class DurabilityHelper {

    private static final Logger log = LoggerFactory.getLogger(DurabilityHelper.class);

    private DurabilityHelper() {}

    public static void fsyncFile(Path file) throws IOException {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            ch.force(true);
        }
    }

    public static void fsyncDir(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (Exception e) {
            log.debug("fsyncDir not supported for {}: {}", dir, e.getMessage());
        }
    }

    public static void writeAndFsync(Path file, byte[] content) throws IOException {
        try (FileChannel ch = FileChannel.open(file,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buf = ByteBuffer.wrap(content);
            while (buf.hasRemaining()) {
                ch.write(buf);
            }
            ch.force(true);
        }
    }

    public static void createFlagFile(Path file) throws IOException {
        Files.createFile(file);
        fsyncFile(file);
        fsyncDir(file.getParent());
    }
}
