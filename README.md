# file-tx-bridge
FileTxBridge is a Java library that coordinates file creation with transactional workflows. It stages writes and only exposes the file after commit, creating a separate commit marker file. If the transaction rolls back, the file is removed in normal scenarios. Designed for crash-safe recovery and idempotent commit/rollback behavior.
