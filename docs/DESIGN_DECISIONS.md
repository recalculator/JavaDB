# JavaDB Design Decisions

This document explains the non-obvious design choices made during implementation. Each section describes what was chosen, what the alternative was, and why.

---

## Tombstones instead of physical row removal

**Decision:** when a row is deleted, its page slot is marked `deleted=1`. The slot is never moved or reclaimed.

**Alternative:** shift subsequent rows left (or maintain a free-list of vacated slots) and reuse the slot.

**Why tombstones:**

The B+ tree stores `key → RowId` mappings, where `RowId` encodes a (page, slot) pair. If a deleted slot were reused by a new row, the old `RowId` stored in the index would point to the new row — a silent data corruption bug.

Tombstones avoid this entirely: the `RowId` of a deleted row becomes permanently unreachable because:
1. Index `delete` removes the key before the tombstone is written.
2. All scan paths check the `deleted` flag and skip tombstoned slots.

The tradeoff is that deletes do not reclaim space. For an educational engine this is acceptable. A production engine would add a vacuum/compaction pass that rewrites the table file and rebuilds the index with updated `RowId`s.

---

## B+ tree rebuilt on every startup

**Decision:** the in-memory B+ tree is discarded on shutdown and rebuilt by scanning the table file on startup.

**Alternative:** persist the B+ tree to disk (a disk-backed B+ tree like those used in SQLite or InnoDB).

**Why rebuild:**

A disk-backed B+ tree requires its own page format, free-page tracking, split/merge persistence, and crash-consistency guarantees separate from the WAL. This adds significant complexity — essentially a second storage system.

Rebuilding from the table file is simple, correct (the table file is the authoritative source), and fast for the sizes this engine is designed for (1M rows rebuilds in under a second on modern hardware). Since the catalog already knows which columns are indexed, rebuild is fully automatic.

The tradeoff is startup latency proportional to data size, and no support for index-only scans (the row data itself must be fetched). These are accepted limitations.

---

## Rule-based query planner

**Decision:** the planner applies a fixed priority list of rules: INDEX_SCAN > INDEX_RANGE_SCAN > FULL_SCAN. No statistics, no cost estimates.

**Alternative:** a cost-based planner that estimates row counts, page I/O cost, and index selectivity.

**Why rule-based:**

A cost-based planner requires maintaining statistics (row counts, column cardinality, histogram buckets) and a cost model calibrated to the storage layer. This is a large, independent subsystem.

For a single-table engine with only one auto-index per table, the rules are always correct:
- An index scan is always better than a full scan for a point lookup on an indexed column.
- An index range scan is always better than a full scan when the range is selective.

When joins, multiple indexes, or subqueries are added, the rule-based approach breaks down — that is the natural forcing function for introducing a cost model.

---

## WAL commit ordering

**Decision:** the ordering is strictly: WAL intent → storage write → WAL COMMIT.

**Why this ordering matters:**

If COMMIT were written before the storage write, a crash between COMMIT and storage write would leave a committed transaction with no data on disk — a durability violation. Recovery would see a COMMIT but find nothing to redo (since there is no redo logic), leaving the database in an inconsistent state.

If the WAL intent were omitted and only COMMIT written, recovery would have no information about what was committed, making redo impossible.

The chosen ordering means: anything with a COMMIT in the WAL is already durable in the table file. Recovery is a confirmation pass, not a redo pass. This simplifies the recovery logic to: discard uncommitted intents, truncate the WAL.

The tradeoff: each mutation requires two WAL flushes (intent + commit) plus one storage write. Throughput is bounded by three sequential disk writes per row. A group-commit optimisation (batch multiple intents into one commit flush) is the obvious next step.

---

## Concurrency model: table-level reader-writer locks

**Decision:** each table has a `ReentrantReadWriteLock`. Reads acquire the read lock (shared); writes acquire the write lock (exclusive).

**Alternatives:**
- Row-level locking — finer granularity, more concurrency for mixed read/write workloads
- MVCC (multi-version concurrency control) — readers never block, writers create new row versions

**Why table-level:**

Row-level locking requires a lock table that scales with the number of active rows and a deadlock detection/prevention mechanism. MVCC requires storing multiple row versions and a garbage-collection mechanism for old versions.

Table-level locking is simple, correct, and verifiable. For a read-heavy workload on a small dataset, it scales well — multiple concurrent readers hold the lock simultaneously with no contention. The limitation is that a single writer blocks all readers for the duration of the write, which is unacceptable in a production system with long-running queries or high write rates.

---

## Known limitations

| Limitation | Root cause | Consequence |
|---|---|---|
| No JOINs | Executor processes one table at a time | Cannot express multi-table queries |
| No multi-statement transactions | No transaction context object; each DML commits immediately | Cannot atomically group changes |
| No disk-backed index | In-memory BPlusTree only | Startup time scales with data size |
| No buffer pool | Every page write calls `RandomAccessFile.write` | Insert throughput bounded by disk I/O |
| No secondary indexes | IndexManager only auto-creates index on first INT column | Range/lookup optimisation unavailable on other columns |
| INT and STRING only | Parser and storage layer do not implement other types | Cannot store floats, booleans, dates |
| No primary key enforcement | Duplicate keys are stored; index returns last-inserted | Queries by primary key may return multiple rows |
| Writers block readers | Table-level exclusive write lock | Long writes cause read latency spikes |
