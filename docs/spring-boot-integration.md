# Spring Boot Integration Guide

This document describes how to integrate FileTxBridge into a Spring Boot application so that file
creation operations participate in JTA transactions managed by Spring.

> **Note:** This guide assumes you are already familiar with Spring Boot and JTA/XA transactions.
> FileTxBridge provides best-effort XA behaviour; review the [known limitations](../README.md#limitations-and-known-trade-offs)
> before adopting it in production.

## Auto-configuration module (recommended)

The `file-tx-bridge-spring-boot-autoconfigure` module (located in the
`file-tx-bridge-spring-boot-autoconfigure/` directory) registers all FileTxBridge beans
automatically — no manual `@Configuration` class required.

**Build and install the autoconfigure module:**
```bash
# 1. Install the core library to the local Maven repository
cd file-tx-bridge && mvn install

# 2. Build the autoconfigure module
cd file-tx-bridge-spring-boot-autoconfigure && mvn package
```

**Add it to your Spring Boot 3.x application:**
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>file-tx-bridge-spring-boot-autoconfigure</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

**Configure it in `application.properties`:**
```properties
filetxbridge.rm-home=/var/lib/my-app/file-tx
# filetxbridge.startup.recovery-enabled=true   # default: true
```

Spring Boot will auto-configure `FileResourceManager`, `FileXaResource`, `RecoveryManager`,
and startup recovery automatically. Skip to [section 5](#5-enlisting-the-xaresource-in-a-transaction)
for how to enlist the auto-configured `FileXaResource` in a transaction.

> **Spring Boot version note:** The autoconfigure module targets Spring Boot 3.x (`jakarta.transaction` namespace).

---

## Manual wiring (custom setup)

The sections below describe manual bean wiring, which is appropriate when requiring custom bean configuration not covered by the auto-configuration.

---

## Table of Contents

1. [Prerequisites](#1-prerequisites)
2. [Maven dependencies](#2-maven-dependencies)
3. [Configuration properties](#3-configuration-properties)
4. [Spring Beans](#4-spring-beans)
5. [Enlisting the XAResource in a transaction](#5-enlisting-the-xaresource-in-a-transaction)
6. [Service layer examples](#6-service-layer-examples)
7. [Startup recovery](#7-startup-recovery)
8. [Testing](#8-testing)
9. [Common pitfalls](#9-common-pitfalls)

---

## 1. Prerequisites

| Requirement | Notes |
|---|---|
| Java 17+ | Required by FileTxBridge |
| Spring Boot 3.x | Uses Jakarta EE namespaces (`jakarta.transaction.*`) |
| JTA transaction manager | Atomikos (`spring-boot-starter-jta-atomikos`) or Narayana |
| FileTxBridge 1.0.0-SNAPSHOT | Built locally via `mvn install` |

> **Spring Boot 2.x note:** Spring Boot 2.x uses the `javax.transaction.*` namespace (Jakarta EE 8).
> FileTxBridge has been migrated to `jakarta.transaction.*` (Jakarta EE 9+) and targets Spring Boot 3.x.
> For Spring Boot 2.x you would need to stay on the previous `javax.transaction-api:1.3` dependency.

---

## 2. Maven dependencies

Add FileTxBridge and the Atomikos JTA starter to your application's `pom.xml`:

```xml
<!-- FileTxBridge -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>file-tx-bridge</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>

<!-- JTA transaction manager — choose one -->

<!-- Option A: Atomikos (Spring Boot auto-configures it) -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-jta-atomikos</artifactId>
</dependency>

<!-- Option B: Narayana (requires additional Narayana Spring Boot starter) -->
<!--
<dependency>
    <groupId>me.snowdrop</groupId>
    <artifactId>narayana-spring-boot-starter</artifactId>
    <version>3.0.0</version>
</dependency>
-->
```

FileTxBridge declares `jakarta.transaction:jakarta.transaction-api` as `provided`. When you add the JTA starter, Spring
Boot supplies the JTA API at runtime, so no explicit `jakarta.transaction-api` dependency is needed
in your application.

---

## 3. Configuration properties

Add the following to `application.properties` (or `application.yml`):

```properties
# Base directory for FileTxBridge staging, tx, backup and lock files.
# Must be on the SAME filesystem as every target file path used in transactions.
filetxbridge.rm-home=/var/lib/my-app/file-tx
```

Create a corresponding `@ConfigurationProperties` class:

```java
package com.myapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "filetxbridge")
public class FileTxBridgeProperties {

    /** Base directory for staging, tx, backup, and lock files. */
    private Path rmHome = Path.of(System.getProperty("java.io.tmpdir"), "file-tx-bridge");

    public Path getRmHome() { return rmHome; }
    public void setRmHome(Path rmHome) { this.rmHome = rmHome; }
}
```

---

## 4. Spring Beans

Register `FileResourceManager`, `FileXaResource`, and `RecoveryManager` as singletons. One
`FileResourceManager` per JVM is required — do not create multiple instances pointing at the same
`rmHome`.

```java
package com.myapp.config;

import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.recovery.RecoveryManager;
import com.example.filetxbridge.xa.FileXaResource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
@EnableConfigurationProperties(FileTxBridgeProperties.class)
public class FileTxBridgeConfig {

    @Bean
    public FileResourceManager fileResourceManager(FileTxBridgeProperties props) throws IOException {
        return new FileResourceManager(props.getRmHome());
    }

    @Bean
    public FileXaResource fileXaResource(FileResourceManager rm) {
        return new FileXaResource(rm);
    }

    @Bean
    public RecoveryManager fileTxRecoveryManager(FileResourceManager rm, FileXaResource xaResource) {
        return new RecoveryManager(rm, xaResource);
    }
}
```

---

## 5. Enlisting the XAResource in a transaction

Spring's `@Transactional` annotation manages transaction boundaries, but enlisting a custom
`XAResource` requires access to the underlying JTA `Transaction` object. The standard JTA API
provides this via `TransactionManager.getTransaction()`.

### 5.1 Obtain the JTA TransactionManager

With Atomikos, Spring Boot exposes a `JtaTransactionManager` bean. You can unwrap the underlying
Atomikos `UserTransactionManager` from it:

```java
import com.atomikos.icatch.jta.UserTransactionManager;
import jakarta.transaction.TransactionManager;
import org.springframework.transaction.jta.JtaTransactionManager;

@Bean
public TransactionManager jtaTransactionManager(JtaTransactionManager springJtaTm) {
    return (TransactionManager) springJtaTm.getTransactionManager();
}
```

With Narayana the bean is available directly:

```java
import com.arjuna.ats.jta.TransactionManager;

@Bean
public jakarta.transaction.TransactionManager jtaTransactionManager() {
    return TransactionManager.transactionManager();
}
```

### 5.2 Obtain the current Xid

FileTxBridge needs the `Xid` of the active transaction to call `session.begin(xid)`. The standard
JTA API does not expose the Xid directly. You must retrieve it from the transaction manager:

**Atomikos:**

```java
import com.atomikos.icatch.jta.TransactionImp;
import jakarta.transaction.Transaction;
import javax.transaction.xa.Xid;

public Xid currentXid(Transaction tx) {
    if (tx instanceof TransactionImp atomikos) {
        return atomikos.getJtaTransaction().getTid();
    }
    throw new IllegalStateException("Cannot extract Xid from transaction: " + tx);
}
```

**Narayana:**

```java
import com.arjuna.ats.jta.transaction.Transaction;
import javax.transaction.xa.Xid;

public Xid currentXid(jakarta.transaction.Transaction tx) {
    if (tx instanceof Transaction narayana) {
        return narayana.getTxId();
    }
    throw new IllegalStateException("Cannot extract Xid from transaction: " + tx);
}
```

> **Why is this needed?** `FileXaSession.begin(xid)` registers the staging context under the given
> Xid so that `prepare()`, `commit()`, and `rollback()` can locate it. The Xid must be the same
> object (or structurally identical) to the one used by the TM for that transaction.

---

## 6. Service layer examples

FileTxBridge is most compelling when the file is part of the business transaction itself.
Typical scenarios include:

- generating a mainframe handoff file while marking the exported rows as sent;
- producing a settlement file while closing the corresponding payments in the database;
- writing a warehouse manifest while moving the batch state from `READY` to `COMPLETED`.

### 6.1 Mainframe batch export scenario

A common pattern is an outbound batch process that must either complete everywhere or fail
everywhere:

1. select the records to export and attach them to a `BatchRun`;
2. update those records so they are marked as part of that batch;
3. stage the outbound file with `FileXaSession.addCreateFile(...)`;
4. advance the batch status to `COMPLETING`;
5. let the JTA transaction manager drive 2PC commit.

If the database work fails, the file never becomes visible. If the file resource cannot
prepare or commit, the database rows and batch status roll back with it. That avoids the
two failure modes that usually hurt operators most: "database says sent, but no file was
delivered" and "file exists, but the batch is still marked failed."

### 6.2 Simplified service example

The following example shows a `BatchExportService` that writes a mainframe batch file as
part of a JTA transaction that also updates the exported records and the batch run row.

```java
package com.myapp.service;

import com.example.filetxbridge.FileResourceManager;
import com.example.filetxbridge.core.WriteMode;
import com.example.filetxbridge.xa.FileXaResource;
import com.example.filetxbridge.xa.FileXaSession;
import jakarta.transaction.Transaction;
import jakarta.transaction.TransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.transaction.xa.Xid;
import java.nio.file.Path;
import java.util.List;

@Service
public class BatchExportService {

    private final BatchRunRepository batchRunRepository;
    private final PaymentRepository paymentRepository;
    private final FileResourceManager fileRm;
    private final FileXaResource fileXaResource;
    private final TransactionManager jtaTm;

    public BatchExportService(BatchRunRepository batchRunRepository,
                              PaymentRepository paymentRepository,
                              FileResourceManager fileRm,
                              FileXaResource fileXaResource,
                              TransactionManager jtaTm) {
        this.batchRunRepository = batchRunRepository;
        this.paymentRepository = paymentRepository;
        this.fileRm = fileRm;
        this.fileXaResource = fileXaResource;
        this.jtaTm = jtaTm;
    }

    @Transactional
    public void exportBatch(String batchId, List<PaymentRecord> records, byte[] fileContent) throws Exception {
        // 1. Update the database state that must commit together with the file handoff
        batchRunRepository.markCompleting(batchId);
        paymentRepository.markSent(batchId, records);

        // 2. Set up the file session for this transaction
        Path target     = Path.of("/data/mainframe/outbound", batchId + ".dat");
        Path commitFlag = Path.of("/data/mainframe/outbound", batchId + ".dat.committed");

        FileXaSession session = new FileXaSession(fileRm);

        // 3. Obtain the JTA Transaction and enlist the XAResource
        Transaction tx = jtaTm.getTransaction();
        tx.enlistResource(session.getXaResource());

        // 4. Extract the Xid from the active transaction and begin the file session
        Xid xid = currentXid(tx);
        session.begin(xid);

        // 5. Stage the outbound file (written to staging dir, not yet visible)
        session.addCreateFile(target, commitFlag, fileContent, WriteMode.CREATE_NEW);

        // 6. Advance the batch status only within the same transaction
        batchRunRepository.markCompleted(batchId);

        // 7. Signal successful completion of the file operations
        session.end();

        // Spring commits the JTA transaction on method exit:
        //   TM calls prepare() then commit() on every enlisted XAResource, including the file one.
        //   The batch file and its .committed marker become visible only after commit.
    }

    /** Extract the Xid from the active Atomikos transaction. */
    private Xid currentXid(Transaction tx) {
        // Atomikos-specific: adapt for Narayana as shown in section 5.2
        if (tx instanceof com.atomikos.icatch.jta.TransactionImp atomikos) {
            return atomikos.getJtaTransaction().getTid();
        }
        throw new IllegalStateException("Cannot extract Xid from: " + tx.getClass());
    }
}
```

### 6.3 Input stream variant (large files)

For large files, use the `InputStream` overload to avoid buffering the entire content in memory:

```java
try (InputStream in = Files.newInputStream(sourceFile)) {
    session.addCreateFile(target, commitFlag, in, WriteMode.CREATE_NEW);
}
```

### 6.4 Replacing an existing file

```java
session.addCreateFile(target, commitFlag, updatedContent, WriteMode.REPLACE_EXISTING);
// On commit  → target atomically replaced with updatedContent
// On rollback → target restored to its previous content from backup
```

---

## 7. Startup recovery

If the JVM crashes after `prepare()` but before `commit()` or `rollback()`, in-doubt transactions
are left on disk. Run recovery at application startup before serving any requests.

Implement `ApplicationListener<ApplicationReadyEvent>` or `SmartInitializingSingleton` to ensure
the JTA transaction manager is fully started before scanning:

```java
package com.myapp.config;

import com.example.filetxbridge.recovery.RecoveryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
public class FileTxStartupRecovery implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(FileTxStartupRecovery.class);

    private final RecoveryManager recoveryManager;

    public FileTxStartupRecovery(RecoveryManager recoveryManager) {
        this.recoveryManager = recoveryManager;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Running FileTxBridge startup recovery...");
        recoveryManager.performStartupRecovery();
        log.info("FileTxBridge startup recovery complete.");
    }
}
```

`performStartupRecovery()` scans `rmHome/tx/` for in-doubt transactions (those with a `PREPARED`
or `COMMITTING` flag but no terminal flag), logs each one, and leaves final resolution to the JTA
transaction manager. The TM will call `recover()` on the enlisted `XAResource` during its own
startup and drive commit or rollback on each in-doubt Xid.

---

## 8. Testing

### Unit / integration tests

Use Spring Boot's `@DataJpaTest` or `@SpringBootTest` with an in-memory or embedded database. For
the file side, use a temporary directory:

```java
@SpringBootTest
class ReportServiceTest {

    @TempDir
    Path tempDir;

    @Autowired
    ReportService reportService;

    @Autowired
    FileTxBridgeProperties props;

    @BeforeEach
    void configureTempDir() {
        // Redirect rmHome to the temp directory for each test
        props.setRmHome(tempDir.resolve("file-tx"));
    }

    @Test
    void reportFileIsCreatedOnCommit() throws Exception {
        byte[] csv = "id,name\n1,Alice\n".getBytes();
        reportService.generateReport("report-001", csv);

        Path target     = tempDir.resolve("reports/report-001.csv");
        Path commitFlag = tempDir.resolve("reports/report-001.csv.committed");
        assertThat(target).exists();
        assertThat(commitFlag).exists();
        assertThat(Files.readAllBytes(target)).isEqualTo(csv);
    }

    @Test
    void reportFileIsRemovedOnRollback() throws Exception {
        // Force the JPA layer to throw so the transaction rolls back
        assertThatThrownBy(() -> reportService.generateReport(null, new byte[0]))
                .isInstanceOf(Exception.class);

        assertThat(tempDir.resolve("reports/null.csv")).doesNotExist();
    }
}
```

### Verifying the commit flag

The `.committed` file is an application-visible signal that the file is durably committed. It is
safe to poll for its existence as a lightweight completeness check without opening the CSV itself:

```java
boolean isCommitted = Files.exists(commitFlagPath);
```

---

## 9. Common pitfalls

| Pitfall | Mitigation |
|---|---|
| `rmHome` on a different filesystem than target files | Always place `rmHome` on the same mount point as every target directory. `ATOMIC_MOVE` across filesystems throws `XAException(XAER_RMERR)`. |
| Creating a new `FileXaSession` outside a transaction | `session.begin(xid)` will throw if there is no active JTA transaction to enlist with. Always call within a `@Transactional` method or after `tm.begin()`. |
| Not calling `session.end()` | If `end()` is not called, the resource manager will not mark the context as ENDED and `prepare()` will fail. |
| Multiple `FileXaSession` objects per transaction | Each `session.addCreateFile()` call on the **same** `FileXaSession` is safe. Creating a second `FileXaSession` in the same transaction is supported but each must be separately enlisted and have `begin(xid)` called with the same Xid. |
| Sharing `rmHome` across multiple JVM instances | Not supported. Each JVM must have a dedicated `rmHome` directory. |
| Not running startup recovery | In-doubt transactions from a previous crash will accumulate on disk. Always call `performStartupRecovery()` at startup. |
| Target path from user input | Resolve and canonicalise all paths before passing them to `addCreateFile()`. FileTxBridge does not sanitise paths for traversal attacks. |
| Staging directory not fsynced on Windows | Directory fsync is a no-op on Windows. On crash, committed flag files may be lost. Consider this limitation when deploying on Windows hosts. |

## Scheduled abandoned-transaction cleanup (on by default)

`RecoveryManager#cleanupAbandonedTransactions(Duration)` sweeps tx directories that
crashed before ever reaching `prepare()`. `recover()` cannot surface those (a
resource must not report branches it never voted YES on), so nothing else ever
cleans them up otherwise -- left unaddressed, this accumulates indefinitely (the
README's "Resource leakage" limitation). The autoconfigure module runs this sweep
automatically on a schedule, transaction-manager-agnostic (Atomikos, Bitronix,
Narayana, or any other JTA implementation):

```properties
# filetxbridge.cleanup.enabled=false   # optional, defaults to true
filetxbridge.cleanup.interval=1h       # optional, defaults to 1h
filetxbridge.cleanup.max-age=24h       # optional, defaults to 24h
```

On by default: what it deletes is already permanently unreachable regardless of
age (see below), so there is no correctness reason to leave the underlying
resource leak unaddressed. Set `filetxbridge.cleanup.enabled=false` to disable if
you would rather manage this yourself.

`max-age` is a forensic/operational grace period, not a safety requirement: a tx
directory with no PREPARED/COMMITTING/COMMITTED/ROLLED_BACK flag and no in-memory
context on the resource instance is already permanently unreachable by `prepare()`
regardless of age (`prepare()` cannot succeed without a prior `start()` on that
exact resource instance, and the JVM that could have called `start()` on it
crashed without ever logging the branch with the transaction manager -- that only
happens once `prepare()` is reached). The wait just avoids destroying evidence
before anyone can look at it if a process is crash-looping.
