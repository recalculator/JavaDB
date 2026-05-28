# JavaDB

An educational relational database engine built from scratch in Java 21.

JavaDB implements the core components that power production databases — SQL parsing, query planning, B+ tree indexing, page-based storage, write-ahead logging, crash recovery, and concurrent access — without depending on any external database libraries.

This is not PostgreSQL. It is a deliberately small, readable, interview-explainable implementation that demonstrates how each layer of a database actually works.

---

## Architecture

```
                        SQL String
                            │
                            ▼
                    ┌───────────────┐
                    │  Lexer/Parser │  tokenises + builds AST
                    └──────┬────────┘
                           │  Statement (sealed interface)
                            ▼
                    ┌───────────────┐
                    │ QueryPlanner  │  chooses FULL_SCAN / INDEX_SCAN /
                    └──────┬────────┘  INDEX_RANGE_SCAN
                           │  ExecutionPlan
                            ▼
                    ┌───────────────┐
                    │   Executor    │  drives reads + writes
                    └──────┬────────┘
              ┌────────────┼────────────┐
              │            │            │
              ▼            ▼            ▼
     ┌──────────────┐ ┌─────────┐ ┌────────┐
     │ StorageEngine│ │  Index  │ │  WAL   │
     │  (TableFile) │ │ Manager │ │        │
     └──────┬───────┘ └────┬────┘ └───┬────┘
            │              │          │
            ▼              ▼          ▼
     ┌──────────┐   ┌───────────┐ ┌──────────────┐
     │  Pages   │   │ BPlusTree │ │  wal.log     │
     │ (4 KB)   │   │ (in-mem)  │ │  (disk)      │
     └──────────┘   └───────────┘ └──────────────┘
            │
            ▼
     ┌──────────────┐
     │  .tbl files  │  one file per table, raw binary pages
     └──────────────┘

  Catalog persistence:  catalog.cat  (text, rewritten atomically on schema change)
  Recovery:             RecoveryManager reads WAL on startup, checkpoints after confirm
  Concurrency:          LockManager  (ReentrantReadWriteLock, table-level)
```

### Component responsibilities

| Component | File(s) | What it does |
|---|---|---|
| **Lexer** | `parser/Lexer.java` | Tokenises SQL into `Token` stream |
| **Parser** | `parser/Parser.java` | Recursive-descent, produces sealed AST nodes |
| **QueryPlanner** | `planner/QueryPlanner.java` | Selects execution strategy per query |
| **Executor** | `executor/Executor.java` | Runs DDL, DML, SELECT, EXPLAIN |
| **StorageEngine** | `storage/StorageEngine.java` | Opens/closes `TableFile` handles |
| **TableFile** | `storage/TableFile.java` | Binary page file; tombstone deletes |
| **Page** | `storage/Page.java` | Fixed 4 KB block; per-slot deleted flag |
| **BPlusTree** | `index/BPlusTree.java` | In-memory B+ tree; binary search; leaf linked list for range scans |
| **IndexManager** | `index/IndexManager.java` | Registry of `table.column → BPlusTree` |
| **WriteAheadLog** | `wal/WriteAheadLog.java` | Append-only log; WAL-before-storage ordering |
| **RecoveryManager** | `recovery/RecoveryManager.java` | Startup recovery + WAL checkpoint |
| **Catalog** | `catalog/Catalog.java` | Schema + named index metadata, persisted atomically |
| **LockManager** | `concurrency/LockManager.java` | Table-level reader-writer locks |

---

## Supported SQL

### Schema

```sql
CREATE TABLE users (
    id   INT,
    name STRING,
    age  INT
);
```

### Explicit indexes

Indexes are created explicitly with `CREATE INDEX`. There is no auto-indexing.

```sql
CREATE INDEX idx_users_id ON users(id);
```

Requirements:
- The column must be of type `INT` (B+ tree keys are integers).
- Only one index per column per table.
- The index name is stored in the catalog and shown in EXPLAIN output.
- On restart, all indexes are rebuilt from the table files using the persisted catalog.
- `CREATE INDEX` on a table with existing rows backfills the index immediately.

### Data manipulation

```sql
INSERT INTO users VALUES (1, 'Ayaan', 20);

UPDATE users SET age = 21 WHERE id = 1;

DELETE FROM users WHERE id = 1;
```

### Queries

```sql
-- Full table scan (no index on this column)
SELECT * FROM users WHERE age > 18;

-- Point lookup — uses B+ tree index if CREATE INDEX was run
SELECT * FROM users WHERE id = 50000;

-- Range scan — uses B+ tree rangeSearch
SELECT * FROM users WHERE id > 1000;
SELECT * FROM users WHERE id <= 500;
SELECT * FROM users WHERE id BETWEEN 100 AND 200;

-- Column projection
SELECT name, age FROM users WHERE id = 1;
```

### EXPLAIN

EXPLAIN shows the plan type, table, index name, and indexed column.

```sql
-- Before CREATE INDEX
EXPLAIN SELECT * FROM users WHERE id = 5;
-- Output:
--   FULL_SCAN
--     table     : users
--     predicate : BinaryOp[left=Column[name=id], operator==, right=Literal[value=5]]

CREATE INDEX idx_users_id ON users(id);

-- After CREATE INDEX
EXPLAIN SELECT * FROM users WHERE id = 5;
-- Output:
--   INDEX_SCAN
--     table     : users
--     index     : idx_users_id
--     index col : id
--     lookup    : id = 5

EXPLAIN SELECT * FROM users WHERE id BETWEEN 100 AND 200;
-- Output:
--   INDEX_RANGE_SCAN
--     table     : users
--     index     : idx_users_id
--     index col : id
--     range     : [100, 200]
```

---

## How it works

### Storage: pages and tombstones

Each table is stored as a flat binary file of 4 KB pages. Rows are packed sequentially within a page as binary fields (4 bytes per INT; length-prefixed UTF-8 per STRING).

**Deletes use tombstones, not slot removal.** When a row is deleted, its slot is marked `deleted=1` on disk. Slot indices never change after a row is written. This guarantees that B+ tree entries (`key → RowId`) remain valid for the lifetime of the database — there is no "RowId drift" after deletes.

### Indexing: explicit B+ tree via CREATE INDEX

Indexes are created explicitly with `CREATE INDEX idx_name ON table(col)`. Only INT columns are supported. Each index is a B+ tree (order 128). Leaf nodes are linked in a doubly-ordered list for efficient range scans.

On restart, the in-memory tree is rebuilt by scanning live rows in the table file — this is safe because tombstoned rows are skipped during the rebuild.

`CREATE INDEX` on a non-empty table backfills the index by scanning the existing rows. Subsequent inserts, updates, and deletes maintain the index incrementally.

Key correctness properties:
- Leaf node search uses binary search (O(log n) within each leaf)
- Deletes remove the key from the tree; subsequent insertions of the same key work correctly
- Range search traverses the leaf linked list rather than re-traversing the tree

### Query planning

The planner reads the WHERE clause and applies these rules in order:

1. `col = value` on a column with an active index → **INDEX_SCAN** (B+ tree point lookup)
2. `col > x`, `col >= x`, `col < x`, `col <= x`, `col BETWEEN x AND y` on an indexed column → **INDEX_RANGE_SCAN** (B+ tree `rangeSearch`)
3. Any other predicate, or no index → **FULL_SCAN**

BETWEEN desugars in the parser to `col >= lo AND col <= hi` — no special planner handling needed. The planner reads index metadata directly from the `Catalog`, so the plan is correct immediately after `CREATE INDEX`.

### Write-ahead logging and recovery

Every mutation follows this ordering:

```
1. Write WAL entry (INSERT / UPDATE / DELETE)   ← intent is durable
2. Flush storage to disk                         ← data is durable
3. Write COMMIT to WAL                           ← transaction confirmed
```

If the process crashes between steps 1 and 3, the WAL entry has no matching COMMIT. On the next startup, `RecoveryManager` discards those incomplete entries (rollback by omission). Since step 2 always precedes step 3, committed transactions are always already on disk — redo is a no-op. After confirming, the WAL is checkpointed (truncated) so it does not grow unboundedly.

### Catalog persistence

Table schemas and named index metadata are persisted to `catalog.cat` in the data directory:

```
TABLE users
COLUMN id INT
COLUMN name STRING
COLUMN age INT
INDEX idx_users_id users id
```

The file is rewritten atomically (temp file + rename) on every schema change. On startup, the catalog is loaded before any queries run, then indexes are rebuilt from the table files.

### Concurrency

Each table has a `ReentrantReadWriteLock`. Multiple readers can hold the read lock simultaneously; a writer requires exclusive access.

---

## Benchmark results

Measured on Apple M-series, Java 21, 100,000-row dataset.
500-query warmup pass before each timed section.

### Insert throughput (100K rows, with index maintained)

| Rows | Time | Throughput |
|---|---|---|
| 100,000 | ~777 ms | ~129,000 rows/s |

**Bottleneck:** one WAL flush + one page write + one B+ tree insert per row. Throughput would improve significantly with batched WAL writes and a buffer pool.

### Point lookup (100K rows, key at midpoint)

| Strategy | Latency |
|---|---|
| Indexed (B+ tree) | ~102 µs |
| Full table scan (100K rows, no match) | ~26 ms |
| **Speedup** | **~257×** |

### Range scan (100K rows, 20% range)

| Query | Rows returned | Time | Throughput |
|---|---|---|---|
| `id BETWEEN 40000 AND 60000` (indexed) | 20,001 | ~2.4 ms | ~8,300 rows/ms |
| `age BETWEEN 40 AND 60` (full scan) | 21,000 | ~12.8 ms | — |
| **Speedup** | — | — | **~5.3×** |

### Concurrent indexed reads (100K dataset, 500 queries/thread)

| Threads | Time | QPS | Scale |
|---|---|---|---|
| 1 | ~2 ms | ~250,000 | 1.00× |
| 4 | ~4 ms | ~500,000 | 2.00× |
| 8 | ~8 ms | ~500,000 | 2.00× |
| 16 | ~16 ms | ~500,000 | 2.00× |

> **Honest caveat:** these benchmarks run on a 100K-row dataset because each insert does a synchronous WAL flush + `RandomAccessFile.seek`. At 1M rows the insert phase takes several minutes. A real database would use a buffer pool and group-commit WAL writes.

---

## Building and running

**Requirements:** Java 21, Maven 3.9+

```bash
# Build
mvn package -q

# Interactive SQL REPL
java -jar target/javadb-1.0.0-jar-with-dependencies.jar data/

# Run tests (96 tests)
mvn test

# Run benchmark suite (100K rows, ~2 minutes)
java -cp target/javadb-1.0.0-jar-with-dependencies.jar \
     com.javadb.benchmark.BenchmarkSuite

# Run benchmark with custom row count
java -cp target/javadb-1.0.0-jar-with-dependencies.jar \
     com.javadb.benchmark.BenchmarkSuite 1000000
```

Or use the provided scripts:

```bash
bash scripts/run-demo.sh       # build + run demo.sql
bash scripts/run-benchmarks.sh # build + run 100K benchmark
```

### REPL example

```
JavaDB 1.0.0
Data directory: /path/to/data
Type SQL ending with ';', or 'exit' to quit.

javadb> CREATE TABLE users (id INT, name STRING, age INT);
Table users created.

javadb> INSERT INTO users VALUES (1, 'Ayaan', 20);
1 row(s) affected

javadb> EXPLAIN SELECT * FROM users WHERE id = 1;
FULL_SCAN
  table     : users
  predicate : BinaryOp[left=Column[name=id], operator==, right=Literal[value=1]]

javadb> CREATE INDEX idx_users_id ON users(id);
Index idx_users_id created.

javadb> EXPLAIN SELECT * FROM users WHERE id = 1;
INDEX_SCAN
  table     : users
  index     : idx_users_id
  index col : id
  lookup    : id = 1

javadb> SELECT * FROM users WHERE id = 1;
id | name | age
--------------------
1 | Ayaan | 20
(1 row(s))

javadb> EXPLAIN SELECT * FROM users WHERE id > 5;
INDEX_RANGE_SCAN
  table     : users
  index     : idx_users_id
  index col : id
  range     : [6, +∞]

javadb> exit
Bye.
```

---

## Test coverage

96 tests across 12 test classes, all passing.

| Test class | What it covers |
|---|---|
| `LexerTest` | Tokenisation, operators, string literals, error cases |
| `ParserTest` | All statement types, WHERE expressions, BETWEEN |
| `StorageTest` | Page serialisation, tombstones, persistence across reopen |
| `BPlusTreeTest` | Insert, search, range scan, large dataset, split correctness |
| `BPlusTreeDeleteTest` | Delete does not corrupt adjacent keys; reinsert; range excludes deleted |
| `CatalogPersistenceTest` | Schema round-trip, named index metadata, duplicate/invalid index rejection |
| `WriteAheadLogTest` | Append, commit, checkpoint/truncate |
| `LockManagerTest` | Multiple concurrent readers, exclusive writer |
| `ExecutorTest` | DDL + all DML + index lookup + concurrent inserts |
| `RestartCorrectnessTest` | Schema/data/index survival across `close()` + reopen, WAL checkpoint, delete correctness |
| `ExplainTest` | EXPLAIN plan types, index name in output, before/after CREATE INDEX, range plans |
| `CreateIndexTest` | Parser, validation (missing table/column/wrong type/duplicate), backfill, restart persistence, planner integration |

---

## Correctness guarantees

| Property | Status |
|---|---|
| Schema survives restart | ✅ `catalog.cat` persisted atomically |
| Row data survives restart | ✅ Binary page files flushed before COMMIT |
| Indexes survive restart | ✅ Rebuilt from table files using catalog metadata |
| Deleted rows excluded from scans | ✅ Tombstone flags checked in all scan paths |
| RowIds stable after deletes | ✅ Tombstone replaces slot; no List.remove shifting |
| Index valid after delete + reinsert | ✅ Delete removes key; insert adds new RowId |
| Uncommitted writes discarded on crash | ✅ WAL COMMIT only written after storage flush |
| WAL does not grow unboundedly | ✅ Checkpointed on every startup |
| Concurrent read safety | ✅ ReentrantReadWriteLock per table |
| CREATE INDEX on existing rows | ✅ Backfill scans all live rows before accepting queries |
| Planner uses index only after CREATE INDEX | ✅ No implicit/auto-indexing |

---

## Known limitations (intentional scope boundaries)

- **No JOINs** — single-table queries only
- **No transactions spanning multiple statements** — each DML is its own transaction
- **No MVCC** — writers block readers at the table level
- **No disk-backed B+ tree** — index rebuilt on every startup (fast for ≤1M rows)
- **No buffer pool** — every page write hits `RandomAccessFile` immediately
- **No secondary indexes on STRING columns** — only INT columns can be indexed
- **No query optimiser cost model** — plan selection is rule-based
- **INT and STRING types only** — no floats, booleans, or dates
- **No primary-key enforcement** — duplicate keys are stored; the index returns the last-inserted duplicate
- **No DROP INDEX** — indexes can only be removed by dropping the table

These are deliberate omissions, not oversights. The goal is a codebase that can be fully understood, explained in an interview, and extended one component at a time.

---

## Future extensions (in priority order)

1. Buffer pool — hold dirty pages in memory, flush in batches
2. Batched WAL commits — group-commit for dramatically higher insert throughput
3. DROP INDEX + multi-column indexes
4. Cost-based planner — row count estimates to choose between index and scan
5. Disk-backed B+ tree — avoid full rebuild on startup for large datasets
6. Multi-statement transactions — `BEGIN` / `COMMIT` / `ROLLBACK`
7. JOIN support — nested-loop join as a first implementation
8. MVCC — snapshot isolation without writer-blocks-reader
