package com.example.filetxbridge.core;

import java.nio.file.Path;

public class FileOperation {

    private final String opId;
    private final Path targetPath;
    private final Path commitFlagPath;
    private final WriteMode mode;
    private final byte[] content;

    public FileOperation(String opId, Path targetPath, Path commitFlagPath, WriteMode mode, byte[] content) {
        this.opId = opId;
        this.targetPath = targetPath;
        this.commitFlagPath = commitFlagPath;
        this.mode = mode;
        this.content = content;
    }

    public String getOpId() {
        return opId;
    }

    public Path getTargetPath() {
        return targetPath;
    }

    public Path getCommitFlagPath() {
        return commitFlagPath;
    }

    public WriteMode getMode() {
        return mode;
    }

    public byte[] getContent() {
        return content;
    }
}
