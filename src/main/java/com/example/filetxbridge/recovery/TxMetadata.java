package com.example.filetxbridge.recovery;

import com.example.filetxbridge.util.XidUtils;

import javax.transaction.xa.Xid;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class TxMetadata {

    private static final String PREFIX_XID = "xid.";
    private static final String KEY_OP_COUNT = "op.count";

    private final String xidDirName;
    private final Xid xid;
    private final List<OpMetadata> operations;

    public TxMetadata(String xidDirName, Xid xid, List<OpMetadata> operations) {
        this.xidDirName = xidDirName;
        this.xid = xid;
        this.operations = new ArrayList<>(operations);
    }

    public String getXidDirName() {
        return xidDirName;
    }

    public Xid getXid() {
        return xid;
    }

    public List<OpMetadata> getOperations() {
        return operations;
    }

    public void saveTo(Path metaFile) throws IOException {
        Properties props = new Properties();
        props.setProperty("xidDirName", xidDirName);
        XidUtils.writeToProperties(xid, props, PREFIX_XID);
        props.setProperty(KEY_OP_COUNT, String.valueOf(operations.size()));
        for (int i = 0; i < operations.size(); i++) {
            OpMetadata op = operations.get(i);
            String pfx = "op." + i + ".";
            props.setProperty(pfx + "opId", op.getOpId());
            props.setProperty(pfx + "targetPath", op.getTargetPath());
            props.setProperty(pfx + "stagingPath", op.getStagingPath());
            props.setProperty(pfx + "commitFlagPath", op.getCommitFlagPath());
            props.setProperty(pfx + "mode", op.getMode());
        }
        try (OutputStream os = Files.newOutputStream(metaFile)) {
            props.store(os, "file-tx-bridge metadata");
        }
    }

    public static TxMetadata loadFrom(Path metaFile) throws IOException {
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(metaFile)) {
            props.load(is);
        }
        String xidDirName = props.getProperty("xidDirName");
        Xid xid = XidUtils.deserialize(props, PREFIX_XID);
        int count = Integer.parseInt(props.getProperty(KEY_OP_COUNT, "0"));
        List<OpMetadata> ops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String pfx = "op." + i + ".";
            ops.add(new OpMetadata(
                    props.getProperty(pfx + "opId"),
                    props.getProperty(pfx + "targetPath"),
                    props.getProperty(pfx + "stagingPath"),
                    props.getProperty(pfx + "commitFlagPath"),
                    props.getProperty(pfx + "mode")
            ));
        }
        return new TxMetadata(xidDirName, xid, ops);
    }

    public static final class OpMetadata {
        private final String opId;
        private final String targetPath;
        private final String stagingPath;
        private final String commitFlagPath;
        private final String mode;

        public OpMetadata(String opId, String targetPath, String stagingPath,
                          String commitFlagPath, String mode) {
            this.opId = opId;
            this.targetPath = targetPath;
            this.stagingPath = stagingPath;
            this.commitFlagPath = commitFlagPath;
            this.mode = mode;
        }

        public String getOpId() { return opId; }
        public String getTargetPath() { return targetPath; }
        public String getStagingPath() { return stagingPath; }
        public String getCommitFlagPath() { return commitFlagPath; }
        public String getMode() { return mode; }
    }
}
