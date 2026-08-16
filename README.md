# file-tx-bridge

> **Warning:** FileTxBridge does not provide full XA transactional guarantees. Filesystems are not transactional resource managers. This library approximates XA-like behavior using staging files, atomic moves, and commit markers, allowing file creation to participate in transactional workflows as closely as practical, but without the strict guarantees of true XA resources.

FileTxBridge is a Java library that coordinates file creation with transactional workflows. It stages writes and only exposes the file after commit, creating a separate commit marker file. If the transaction rolls back, the file is removed in normal scenarios. Designed for crash-safe recovery and idempotent commit/rollback behavior.

## Features

- **XA 2PC participant**: `FileXaResource` implements `javax.transaction.xa.XAResource` and can be enlisted in any JTA-compliant transaction manager.
- **Atomic file operations**: uses `Files.move(ATOMIC_MOVE)` to swap the staged file into place; requires the staging directory to reside on the same filesystem as the target.
- **Commit flag file**: after a successful commit a separate, application-visible flag file (e.g. `target.committed`) is created, confirming durable completion.
- **Crash recovery**: flag files (`PREPARED`, `COMMITTING`, `COMMITTED`, `ROLLING_BACK`, `ROLLED_BACK`) and persisted `meta.properties` allow `RecoveryManager` to reconstruct and complete in-doubt transactions after a JVM crash.
- **Idempotent commit/rollback**: repeated calls are safe.
- **`REPLACE_EXISTING` mode**: atomically replaces an existing target; on rollback the original content is restored.

## Requirements

- Java 17+
- A JTA transaction manager (e.g. Bitronix, Atomikos, Narayana, Jakarta Transactions)
- Staging directory on the **same filesystem** as target files (for `ATOMIC_MOVE` support)

## Maven coordinates

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>file-tx-bridge</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

The library declares `jakarta.transaction:jakarta.transaction-api` as `provided` — your application or container must supply the JTA API at runtime.

## Quick start

```java
import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.core.WriteMode;
import com.example.filetxbridge.recovery.RecoveryManager;
import com.example.filetxbridge.xa.FileXaResource;
import com.example.filetxbridge.xa.FileXaSession;
import javax.transaction.xa.Xid;
import java.nio.file.Path;

// 1. Create the resource manager once per JVM (or application lifecycle)
Path rmHome = Path.of("/var/lib/my-app/file-tx");
FileResourceManager rm = new FileResourceManager(rmHome);

// 2. Run startup recovery (logs in-doubt Xids; TM will drive resolution)
FileXaResource xaResource = new FileXaResource(rm);
RecoveryManager recovery = new RecoveryManager(rm, xaResource);
recovery.performStartupRecovery();

// 3. Per-transaction usage — pseudocode with a JTA TransactionManager (tm)
tm.begin();
jakarta.transaction.Transaction tx = tm.getTransaction();

FileXaSession session = new FileXaSession(rm);
tx.enlistResource(session.getXaResource());   // enlist with the TM

Xid xid = /* obtain current Xid from TM */ ...;
session.begin(xid);

Path target     = Path.of("/data/reports/report-2024.csv");
Path commitFlag = Path.of("/data/reports/report-2024.csv.committed");
session.addCreateFile(target, commitFlag, csvBytes, WriteMode.CREATE_NEW);

session.end();   // signals TMSUCCESS to the XAResource

tm.commit();     // TM drives prepare() then commit() on all enlisted resources
// After commit:
//   /data/reports/report-2024.csv          — the report file
//   /data/reports/report-2024.csv.committed — durable commit marker
```

### Replace existing file

```java
session.addCreateFile(target, commitFlag, newBytes, WriteMode.REPLACE_EXISTING);
// On commit  -> target contains newBytes
// On rollback -> target is restored to its previous content
```

### Input stream variant

```java
try (InputStream in = Files.newInputStream(sourceFile)) {
    session.addCreateFile(target, commitFlag, in, WriteMode.CREATE_NEW);
}
// Content is streamed directly to the staging file; not buffered in heap.
```

## Directory layout

```
rmHome/
  staging/          <- temporary staged files (<xidKey>-<opId>.tmp)
  backup/           <- original files backed up during REPLACE_EXISTING commit
  tx/
    <xidKey>/
      meta.properties   <- serialized Xid + operation list
      PREPARED          <- flag: prepare() completed
      COMMITTING        <- flag: commit() started
      COMMITTED         <- flag: commit() completed (durable)
      ROLLING_BACK      <- flag: rollback() started
      ROLLED_BACK       <- flag: rollback() completed
  locks/            <- advisory lock files (PathLockManager)
```

## Recovery

`FileXaResource.recover(TMSTARTRSCAN)` scans `rmHome/tx/` and returns all Xids whose tx directory has a `PREPARED` or `COMMITTING` flag but neither `COMMITTED` nor `ROLLED_BACK`. The TM calls this at startup and drives the final decision.

```java
Xid[] inDoubt = xaResource.recover(XAResource.TMSTARTRSCAN);
for (Xid xid : inDoubt) {
    // TM decides based on its own log
    xaResource.commit(xid, false);   // or xaResource.rollback(xid)
}
```

## Limitations and known trade-offs

### 1. No true transactional isolation

Filesystems do not enforce transactional isolation. Two transactions may attempt to create or replace the same target file simultaneously, and external processes can modify or delete the file during the transaction. Locking is implemented via advisory lock files or OS locks, which other processes can ignore. This creates a risk of race conditions and potential heuristic outcomes.

### 2. Atomic rename constraints

The commit step relies on atomic rename (`Files.move(ATOMIC_MOVE)`). This works only within the same filesystem or mount point — staging and target directories must reside on the same filesystem. Some filesystems (including certain configurations of NFS and other network filesystems) do not guarantee true atomic rename semantics, which can violate the expected commit behaviour.

### 3. Weak rollback guarantees for replacements

When a `REPLACE_EXISTING` operation is committed, the sequence is: back up the old file, then atomically move the staged file to the target. A crash between these two steps can leave an intermediate state (target deleted, backup present) that requires recovery logic to restore. Backup files remain on disk until recovery completes, increasing storage usage and operational complexity.

### 4. Durability depends on correct fsync usage

Crash-safe behaviour requires `fsync()` on both file contents and the containing directory after every flag creation or rename. Directory fsync (`FileChannel.open(dir, READ).force(true)`) is not portable — it is silently skipped on Windows and some JVMs. Many filesystems reorder write operations unless explicitly flushed, meaning that after a power loss, committed flags or renames may not be visible on restart.

### 5. Higher probability of heuristic outcomes

XA assumes that once a resource has prepared, it can always complete commit or rollback. A filesystem participant can fail to commit after prepare due to: permission changes, manual deletion of staging files, a full disk, or a missing staging file. Such failures produce heuristic commit/rollback conditions that require manual intervention to resolve.

### 6. Resource leakage

Prepared but uncommitted transactions hold resources: staging files, backup files, metadata directories, and advisory lock files. If the transaction manager crashes and loses its own log, these artifacts remain indefinitely and must be cleaned up by a recovery process or manual intervention.

### 7. Performance overhead

Compared to a plain file write, the XA-style process requires a staging write, a metadata write, multiple `fsync` operations across several files and directories, and potentially a backup creation. This significantly increases I/O latency per operation.

### 8. External interference

The filesystem is a shared, unmanaged namespace. Other processes may delete or modify staging files, change permissions, or replace directories with symlinks. Any such action can silently break transactional guarantees without the library being aware.

### 9. Security and path safety

If target paths or commit-flag paths originate from external input, callers must validate them before passing to the library. The library does not perform path traversal sanitisation (`../`), symlink resolution, or time-of-check/time-of-use hardening. Applications operating on untrusted input must resolve and validate all paths before use.

### 10. Incomplete XA feature parity

A filesystem resource will typically lack full XA capabilities including precise timeout enforcement, deadlock detection, strict isolation levels, and a durable transaction log comparable to a database engine. This library provides best-effort XA compatibility as closely as filesystem semantics allow, not full equivalence to a true XA resource manager.

## Further reading

- [Spring Boot Integration Guide](docs/spring-boot-integration.md) — step-by-step guide for wiring FileTxBridge into a Spring Boot application with Atomikos or Narayana.
- [Atomikos Integration Guide](docs/atomikos-integration.md) — how to register a `FileXaResource` with Atomikos using the `file-tx-bridge-atomikos` module, so it survives a JVM restart. Needed whether or not Spring Boot is in the picture: registered manually in plain Java, or automatically when combined with `file-tx-bridge-spring-boot-autoconfigure` (which registers the Spring bean, but does not perform this Atomikos-specific registration on its own).

## Building

```
# Core library
mvn package          # produces target/file-tx-bridge-1.0.0-SNAPSHOT.jar
mvn test             # runs all JUnit 5 tests
mvn install          # installs to local Maven repository (required before building the autoconfigure/atomikos modules)

# Spring Boot autoconfigure module
cd file-tx-bridge-spring-boot-autoconfigure
mvn package          # produces target/file-tx-bridge-spring-boot-autoconfigure-1.0.0-SNAPSHOT.jar
mvn test             # runs auto-configuration tests

# Atomikos integration module
cd file-tx-bridge-atomikos
mvn package          # produces target/file-tx-bridge-atomikos-1.0.0-SNAPSHOT.jar
mvn test             # runs registration tests
```
