package com.example.filetxbridge.util;

import javax.transaction.xa.Xid;
import java.util.Arrays;
import java.util.Base64;
import java.util.Properties;

public final class XidUtils {

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private XidUtils() {}

    public static String toDirectoryName(Xid xid) {
        String globalPart = ENCODER.encodeToString(xid.getGlobalTransactionId());
        String branchPart = ENCODER.encodeToString(xid.getBranchQualifier());
        return xid.getFormatId() + "-" + globalPart + "-" + branchPart;
    }

    public static String serialize(Xid xid, String prefix) {
        Properties props = new Properties();
        props.setProperty(prefix + "formatId", String.valueOf(xid.getFormatId()));
        props.setProperty(prefix + "globalTxId", ENCODER.encodeToString(xid.getGlobalTransactionId()));
        props.setProperty(prefix + "branchQualifier", ENCODER.encodeToString(xid.getBranchQualifier()));
        return props.toString();
    }

    public static void writeToProperties(Xid xid, Properties props, String prefix) {
        props.setProperty(prefix + "formatId", String.valueOf(xid.getFormatId()));
        props.setProperty(prefix + "globalTxId", ENCODER.encodeToString(xid.getGlobalTransactionId()));
        props.setProperty(prefix + "branchQualifier", ENCODER.encodeToString(xid.getBranchQualifier()));
    }

    public static Xid deserialize(Properties props, String prefix) {
        int formatId = Integer.parseInt(props.getProperty(prefix + "formatId"));
        byte[] globalTxId = DECODER.decode(props.getProperty(prefix + "globalTxId"));
        byte[] branchQualifier = DECODER.decode(props.getProperty(prefix + "branchQualifier"));
        return new SimpleXid(formatId, globalTxId, branchQualifier);
    }

    public static final class SimpleXid implements Xid {

        private final int formatId;
        private final byte[] globalTxId;
        private final byte[] branchQualifier;

        public SimpleXid(int formatId, byte[] globalTxId, byte[] branchQualifier) {
            this.formatId = formatId;
            this.globalTxId = globalTxId.clone();
            this.branchQualifier = branchQualifier.clone();
        }

        @Override
        public int getFormatId() {
            return formatId;
        }

        @Override
        public byte[] getGlobalTransactionId() {
            return globalTxId.clone();
        }

        @Override
        public byte[] getBranchQualifier() {
            return branchQualifier.clone();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Xid other)) return false;
            return formatId == other.getFormatId()
                    && Arrays.equals(globalTxId, other.getGlobalTransactionId())
                    && Arrays.equals(branchQualifier, other.getBranchQualifier());
        }

        @Override
        public int hashCode() {
            int result = formatId;
            result = 31 * result + Arrays.hashCode(globalTxId);
            result = 31 * result + Arrays.hashCode(branchQualifier);
            return result;
        }

        @Override
        public String toString() {
            return "SimpleXid{formatId=" + formatId
                    + ", globalTxId=" + ENCODER.encodeToString(globalTxId)
                    + ", branchQualifier=" + ENCODER.encodeToString(branchQualifier) + "}";
        }
    }
}
