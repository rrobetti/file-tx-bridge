package com.example.filetxbridge.core;

import javax.transaction.xa.Xid;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FileTxContext {

    private final Xid xid;
    private final List<FileOperation> operations;
    private TxState state;
    private final Path txDir;

    public FileTxContext(Xid xid, Path txDir) {
        this.xid = xid;
        this.txDir = txDir;
        this.operations = new ArrayList<>();
        this.state = TxState.ACTIVE;
    }

    public Xid getXid() {
        return xid;
    }

    public List<FileOperation> getOperations() {
        return Collections.unmodifiableList(operations);
    }

    public TxState getState() {
        return state;
    }

    public void setState(TxState state) {
        this.state = state;
    }

    public Path getTxDir() {
        return txDir;
    }

    public void addOperation(FileOperation operation) {
        operations.add(operation);
    }
}
