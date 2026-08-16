# Atomikos Integration Guide

This document describes how to register a `FileXaResource` with Atomikos so it can
be enlisted in a transaction and survive a JVM restart. This applies whether your
application uses Atomikos directly or through Spring Boot (e.g. via
`spring-boot-starter-jta-atomikos`) — the [Spring Boot Integration
Guide](spring-boot-integration.md) registers `FileXaResource` as a Spring bean, but
does not perform this Atomikos-specific registration on its own.

> **Note:** This guide assumes you are already familiar with Atomikos and JTA/XA
> transactions. FileTxBridge provides best-effort XA behaviour; review the
> [known limitations](../README.md#limitations-and-known-trade-offs) before
> adopting it in production.

## Why registration is required

Calling `Transaction#enlistResource(xaResource)` directly, without registering the
resource first, is rejected by Atomikos:

```
There is no registered resource that can recover the given XAResource instance.
Please register a corresponding resource first.
```

Atomikos never persists an `XAResource` object itself in its own transaction log —
only the resource's unique name. After a crash, Atomikos needs a factory it can ask
for a fresh `XAResource` instance by that name before it can drive
`recover()`/`commit()`/`rollback()` on it during recovery. Registering a
`com.atomikos.datasource.xa.XATransactionalResource` is how you provide that
factory — the same mechanism Atomikos itself uses for JDBC/JMS connection pools.

## The `file-tx-bridge-atomikos` module

The `file-tx-bridge-atomikos` module ships `FileTxBridgeAtomikosResource`, a ready
`XATransactionalResource` wrapper for `FileXaResource`. It works two ways: wired
manually in plain Java, or automatically as a Spring Boot auto-configuration.

**Build and install it:**
```bash
# 1. Install the core library to the local Maven repository
cd file-tx-bridge && mvn install

# 2. Build the Atomikos module
cd file-tx-bridge-atomikos && mvn package
```

**Add it to your application:**
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>file-tx-bridge-atomikos</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

### Plain Java usage

**Register the resource once, at startup, before any transaction uses it:**
```java
FileResourceManager rm = new FileResourceManager(rmHome);
FileXaResource xaResource = new FileXaResource(rm);

Configuration.addResource(
        new FileTxBridgeAtomikosResource("my-file-tx-bridge-resource", xaResource));
```

The unique resource name (`"my-file-tx-bridge-resource"` above) must stay the same
across restarts of the JVM that owns a given `rmHome` — it is how Atomikos matches
this registration back to the in-doubt transactions it finds in its own log.

**Enlist it in a transaction as usual:**
```java
UserTransactionManager utm = new UserTransactionManager();
utm.init();

utm.begin();
Transaction tx = utm.getTransaction();
tx.enlistResource(xaResource);

// ... stage file operations via the current transaction's Xid ...

tx.delistResource(xaResource, XAResource.TMSUCCESS);
utm.commit();
```

### Spring Boot usage (automatic)

If your Spring Boot application already has a `FileXaResource` bean — typically
from `file-tx-bridge-spring-boot-autoconfigure` — adding `file-tx-bridge-atomikos`
to the classpath is enough on its own. Its auto-configuration
(`FileTxBridgeAtomikosAutoConfiguration`) detects that bean and registers it with
Atomikos automatically at startup, and unregisters it at shutdown. No code required.

```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>file-tx-bridge-spring-boot-autoconfigure</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
<dependency>
    <groupId>com.example</groupId>
    <artifactId>file-tx-bridge-atomikos</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

```properties
filetxbridge.rm-home=/var/lib/my-app/file-tx
filetxbridge.atomikos.resource-name=my-app-file-tx-bridge   # optional, defaults to "file-tx-bridge"
```

If no `FileXaResource` bean is present (`file-tx-bridge-spring-boot-autoconfigure`
not on the classpath, and none defined manually), this auto-configuration simply
does not activate — it never fails startup for lacking one.

> **Scheduled abandoned-transaction cleanup** (sweeping tx directories that crashed
> before ever reaching `prepare()`) is transaction-manager-agnostic, not specific
> to Atomikos -- see the [Spring Boot Integration
> Guide](spring-boot-integration.md#scheduled-abandoned-transaction-cleanup-opt-in)
> for that.

## Namespace note

`com.atomikos:transactions-jta` targets the pre-Jakarta-EE9 `javax.transaction.*`
package namespace. The core `file-tx-bridge` module's own `jakarta.transaction-api`
dependency is version 2.0.1, which uses the post-EE9 `jakarta.transaction.*`
namespace — a different set of classes. If your application code (not just this
integration module) needs `javax.transaction.Transaction`,
`javax.transaction.TransactionManager`, or similar, add
`javax.transaction:javax.transaction-api` to your own classpath; it is not pulled in
transitively by either `file-tx-bridge` or `file-tx-bridge-atomikos`.

## Heuristic outcomes

If one operation in a transaction already durably committed while another cannot
complete (a permission change, a full disk, or a deleted staging file after
`prepare()` — see the README's "Higher probability of heuristic outcomes"
limitation), `commit()` reports `XA_HEURHAZ`. Atomikos's own recovery manager
recognizes this: it will keep retrying the commit on later recovery scans rather
than giving up immediately, since the underlying problem may still clear and let
the transaction reach a normal, fully committed outcome.
