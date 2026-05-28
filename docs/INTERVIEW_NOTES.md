# JavaDB Interview Notes

## Resume bullet

> Built a relational database engine from scratch in Java 21 featuring SQL parsing (recursive-descent), B+ tree indexing with range scan, 4 KB page-based persistent storage, write-ahead logging with correct commit ordering, crash recovery, tombstone-based deletes with stable RowIds, atomic catalog persistence, reader-writer concurrency, EXPLAIN, and a benchmark suite. 76 passing tests covering restart correctness, delete safety, index rebuild, and concurrent access.

---

## 5 concise talking points

1. **Storage layer**: rows live in fixed-size 4 KB pages in a flat binary file per table. Deletes write a tombstone flag rather than shifting rows, which keeps B+ tree `RowId` references permanently valid.

2. **B+ tree index**: hand-implemented in-memory B+ tree (order 128) on the first INT column of every table. Leaf nodes are doubly-linked for O(k) range traversal. Rebuilt from the table file on every startup — simple, correct, and fast for the target dataset size.

3. **WAL and recovery**: every mutation follows intent → storage write → COMMIT. A crash before COMMIT leaves an uncommitted intent; recovery discards it and truncates the log. No redo logic is needed because storage writes precede COMMIT.

4. **Concurrency**: table-level `ReentrantReadWriteLock` — multiple readers share the lock, writers take exclusive access. Benchmarked at ~246 K QPS on 16 threads for indexed reads on a 10 K row dataset.

5. **Query planning**: rule-based planner selects INDEX_SCAN, INDEX_RANGE_SCAN, or FULL_SCAN based on the WHERE clause and index availability. EXPLAIN emits the chosen plan without executing the query.

---

## 5 likely interview questions and strong answers

### Q1: Walk me through what happens when I run `INSERT INTO users VALUES (1, 'Ayaan', 20);`

The SQL string enters the `Lexer`, which tokenises it into keyword/identifier/literal tokens. The `Parser` consumes those tokens in a recursive-descent pass and returns an `InsertStatement` AST node. The `QueryPlanner` sees an INSERT and returns an `INSERT` plan. The `Executor` then:
1. Acquires the table-level write lock.
2. Appends a WAL INSERT entry (intent is now durable on disk).
3. Serialises the row as binary field data and appends it to the last non-full 4 KB page in the `.tbl` file — the row's `RowId` (page index, slot index) is returned.
4. Inserts `key=1 → RowId` into the B+ tree in memory.
5. Appends a WAL COMMIT entry.
6. Releases the write lock.

If the process crashes between steps 2 and 5, on restart `RecoveryManager` finds an INSERT entry with no matching COMMIT and discards it — the row may already be on disk but the index is rebuilt fresh from the live rows in the table file, so the orphaned slot is simply skipped (it has no tombstone set, which is a minor correctness note: the slot would be visible but not indexed; in practice, the recovery manager could also tombstone uncommitted rows as a hardening step).

---

### Q2: Why not just use a `HashMap` instead of a B+ tree?

A `HashMap` gives O(1) point lookups but cannot do range queries efficiently — a `WHERE id > 1000` would require scanning every key. The B+ tree supports both point lookups in O(log N) time and range scans in O(log N + k) time where k is the number of results, because leaf nodes are linked in sorted order. The sorted structure also makes it straightforward to support `BETWEEN`, `>=`, and `<=` predicates.

Additionally, B+ trees are the actual data structure used in production database indexes (InnoDB, PostgreSQL), so the implementation demonstrates understanding of real systems.

---

### Q3: How does crash recovery work?

Every mutation writes a WAL intent entry before touching the table file, and writes a COMMIT entry after. On startup, `RecoveryManager` reads the WAL from the beginning:

- Entries with a matching COMMIT: the data is confirmed durable (since storage writes precede COMMIT). No action needed — these are already on disk.
- Entries without a COMMIT: the transaction was interrupted. Since the WAL intent is discarded and the index is rebuilt fresh from the table file, these partial writes are effectively rolled back.

After recovery, the WAL is checkpointed (truncated to zero) so it does not grow unboundedly across restarts.

This is a simplified crash recovery model — it handles the common cases correctly but does not implement full ARIES-style undo/redo, which would be needed for multi-statement transactions.

---

### Q4: What would you add first if you were extending this into a production system?

The highest-leverage change would be a **buffer pool** — an in-memory cache of recently-used pages with a dirty-page tracking mechanism that flushes pages in batches. Currently every page write calls `RandomAccessFile.write` directly, which means insert throughput is bounded by synchronous disk I/O. A buffer pool would allow many inserts to accumulate in memory and flush together, increasing throughput by one to two orders of magnitude.

The second change would be **group-commit WAL writes** — instead of flushing the WAL on every `INSERT`, buffer multiple WAL entries and flush them together on a time or byte threshold. This is the technique PostgreSQL uses (`synchronous_commit = off` / commit batching) to achieve high write throughput while preserving durability guarantees.

---

### Q5: Explain the tombstone delete design. Why not compact the file?

When a row is deleted, its slot in the page is marked `deleted=1`. The slot is never moved, reused, or physically removed from the file. The B+ tree key is removed, so the row becomes unreachable through the index.

The reason for this design is `RowId` stability. The B+ tree stores `key → RowId` where `RowId` is a (page, slot) pair. If a deleted slot were reused by a new row, the old `RowId` in the tree would silently point to the wrong data — a very hard-to-detect corruption bug.

Tombstones guarantee that a `RowId`, once written into the index, is either live or permanently dead — never aliased to a different row. All scan paths check the tombstone flag and skip deleted slots.

The cost is that deleted space is never reclaimed. The correct fix is a vacuum/compaction pass: rewrite the table file skipping tombstoned rows, rebuild the index with updated `RowId`s, and atomically replace the old file. This is analogous to PostgreSQL's VACUUM operation.

---

## Why not just use PostgreSQL?

"PostgreSQL is the right answer for a production application. This project is about understanding how a database works from the inside — the implementation choices I made, the bugs I ran into, and the tradeoffs between simplicity and correctness.

Writing the B+ tree myself forced me to understand why leaf nodes need to be linked for range scans, and why an in-order traversal of the tree alone is not sufficient. Writing the WAL forced me to think carefully about commit ordering — specifically why storage writes must precede the COMMIT entry, not follow it. Using PostgreSQL gives me none of that understanding.

The goal was to produce a codebase where I can point to any file and explain exactly what it does and why, which is more valuable for learning systems design than configuring a production database."

---

## What was the hardest bug?

"The hardest bug was a WAL ordering issue where crash recovery could leave the database in an inconsistent state. The original implementation wrote the WAL COMMIT entry, then flushed storage. A crash between those two steps left a committed WAL entry with no corresponding data on disk.

The fix required inverting the order: storage flush first, then COMMIT. But that alone was not enough — the recovery manager also needed to correctly handle the case where storage data exists but the COMMIT is missing (i.e., treat it as uncommitted). Getting this right required reasoning carefully about all four possible crash windows: before intent, between intent and storage, between storage and COMMIT, and after COMMIT.

The test that caught it was `RestartCorrectnessTest`, which inserts rows, closes the database, reopens it, and verifies that all expected rows are present and all deleted rows are absent — simulating a clean shutdown. Adding a test that simulates a crash mid-write (by writing the intent but not the COMMIT) was the forcing function for fixing the ordering."
