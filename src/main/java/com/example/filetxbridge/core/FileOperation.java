package com.example.filetxbridge.core;

import java.nio.file.Path;

public class FileOperation {

    private final String opId;
    private final Path targetPath;
    private final Path commitFlagPath;
    private final WriteMode mode;
    private final byte[] content;

    // Set by prepare() once it creates a parent directory that did not already
    // exist -- the topmost newly-created ancestor. Null means the directory
    // already existed at prepare()-time, so rollback() must not remove it.
    private Path targetDirCreationRoot;
    private Path commitFlagDirCreationRoot;

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
        return content == null ? null : content.clone();
    }

    public Path getTargetDirCreationRoot() {
        return targetDirCreationRoot;
    }

    public void setTargetDirCreationRoot(Path targetDirCreationRoot) {
        this.targetDirCreationRoot = targetDirCreationRoot;
    }

    public Path getCommitFlagDirCreationRoot() {
        return commitFlagDirCreationRoot;
    }

    public void setCommitFlagDirCreationRoot(Path commitFlagDirCreationRoot) {
        this.commitFlagDirCreationRoot = commitFlagDirCreationRoot;
    }
}
