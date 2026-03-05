# file-tx-bridge

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

The library declares `javax.transaction:javax.transaction-api` as `provided` — your application or container must supply the JTA API at runtime.

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
javax.transaction.Transaction tx = tm.getTransaction();

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

- **Same-filesystem requirement**: Staging directory must be on the same filesystem as every target. `ATOMIC_MOVE` across filesystems is not supported and throws `XAException(XAER_RMERR)`.
- **Advisory locks only**: `PathLockManager` uses `java.nio.channels.FileLock` (advisory). External processes that ignore the lock file can interfere.
- **Best-effort directory fsync**: Directory fsync is attempted via `FileChannel.open(dir, READ).force(true)`. On Windows and some JVMs this may silently do nothing; the flag files are still fsynced individually.
- **No automatic heuristic resolution**: The library reports in-doubt Xids to the TM; it does not make autonomous commit/rollback decisions.
- **Single RM per JVM**: Sharing one `rmHome` across multiple JVMs simultaneously is not supported and may corrupt the tx log.

## Building

```
mvn package          # produces target/file-tx-bridge-1.0.0-SNAPSHOT.jar
mvn test             # runs all JUnit 5 tests
```
