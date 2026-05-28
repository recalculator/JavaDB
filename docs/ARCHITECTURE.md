# JavaDB Architecture

## High-level system overview

JavaDB is a single-process, single-node relational database engine. There is no server process or network protocol — the `Database` class is the entry point and embeds all layers. Storage is a directory of binary files on the local filesystem.

```
┌─────────────────────────────────────────────────────────┐
│                      Application / CLI                   │
│                      (Main.java / Database.java)         │
└────────────────────────────┬────────────────────────────┘
                             │ SQL string
                             ▼
              ┌──────────────────────────┐
              │  Lexer  →  Parser        │  tokenise + build AST
              └──────────────┬───────────┘
                             │ Statement (sealed interface)
                             ▼
              ┌──────────────────────────┐
              │      QueryPlanner        │  rule-based plan selection
              └──────────────┬───────────┘
                             │ ExecutionPlan
                             ▼
              ┌──────────────────────────┐
              │       Executor           │  drives all read/write ops
              └────┬──────────┬──────────┘
                   │          │
         ┌─────────▼──┐  ┌───▼──────────┐
         │StorageEngine│  │ IndexManager │
         │ (TableFile) │  │ (BPlusTree)  │
         └─────┬───────┘  └──────────────┘
               │
        ┌──────▼──────┐   ┌──────────────┐   ┌─────────────┐
        │ Page (4 KB) │   │ WriteAheadLog│   │   Catalog   │
        │  .tbl files │   │  wal.log     │   │ catalog.cat │
        └─────────────┘   └──────────────┘   └─────────────┘
```

## Query lifecycle: SQL string → result

### Step 1: Lexing

`Lexer.java` scans the input character by character and emits a flat list of `Token` objects. Each token carries a `TokenType` (keyword, identifier, literal, operator, punctuation) and a string value. Whitespace is discarded. The lexer is a hand-written state machine — no external tokeniser library is used.

### Step 2: Parsing

`Parser.java` is a hand-written recursive-descent parser. It consumes the token stream produced by the lexer and constructs an AST (Abstract Syntax Tree). Every node is a `record` implementing the sealed `Statement` interface:

- `CreateTableStatement` — column names + types
- `InsertStatement` — literal value list
- `SelectStatement` — optional column list, table name, optional `WHERE` expression
- `UpdateStatement` — assignments + optional `WHERE`
- `DeleteStatement` — optional `WHERE`
- `ExplainStatement` — wraps a `SelectStatement`

`WHERE` expressions are parsed recursively into an `Expression` tree: `BinaryOp`, `Column`, and `Literal` nodes. `BETWEEN` desugars at parse time into `col >= lo AND col <= hi`.

### Step 3: Query planning

`QueryPlanner.java` inspects the parsed `Statement` and returns an `ExecutionPlan`. The planner reads from the `Catalog` to know which columns have explicit indexes. Plan selection is purely rule-based:

| Condition | Plan |
|---|---|
| WHERE `indexed_col = value` | `INDEX_SCAN` |
| WHERE `indexed_col op value` (range) | `INDEX_RANGE_SCAN` |
| Any other / no WHERE, or no index | `FULL_SCAN` |

Indexes are **explicit only** — created with `CREATE INDEX idx_name ON table(col)`. There is no auto-indexing. The planner reads `IndexMetadata` from the `Catalog`, which includes the index name carried through to `EXPLAIN` output. EXPLAIN returns the plan without executing it.

### Step 4: Execution

`Executor.java` takes an `ExecutionPlan` and drives the storage/index layers to produce a `QueryResult`.

- **DDL** (`CREATE TABLE`): registers schema in `Catalog`, writes to `catalog.cat`, opens a new `TableFile`.
- **DDL** (`CREATE INDEX`): validates table/column/type/uniqueness via `Catalog.registerIndex`, creates the in-memory `BPlusTree` via `IndexManager`, then backfills by scanning all live rows in the `TableFile`.
- **INSERT**: acquires write lock → writes WAL INSERT entry → appends row to `TableFile` → inserts key into `BPlusTree` → writes WAL COMMIT.
- **SELECT (full scan)**: acquires read lock → iterates all pages → skips tombstoned slots → applies `WHERE` predicate → projects columns.
- **SELECT (index scan)**: acquires read lock → B+ tree point lookup → fetches the specific row by `RowId` → applies any remaining predicates.
- **SELECT (range scan)**: acquires read lock → B+ tree `rangeSearch` returns all `RowId`s in range → fetches each row.
- **UPDATE**: acquires write lock → full scan to find matching rows → WAL UPDATE entry → mutate row in page → WAL COMMIT.
- **DELETE**: acquires write lock → full scan to find matching rows → WAL DELETE entry → set tombstone flag in page → remove key from index → WAL COMMIT.

---

## Storage layout

### Table files

Each table is stored in a single binary file `<data-dir>/<table>.tbl`. The file is a flat sequence of 4 KB pages. There is no free-space map or page directory — new rows are always appended to the last page or to a new page if the last page is full.

**Page layout (4 096 bytes):**

```
[4 bytes] rowCount   — number of slots written in this page
[4 bytes] reserved   — unused, pads to even offset
[variable] slot[0] .. slot[n-1]
```

**Slot layout:**

```
[1 byte]  deleted    — 0 = live, 1 = tombstoned
[per-column data]
  INT:    [4 bytes] big-endian signed int
  STRING: [4 bytes] byte length + [N bytes] UTF-8
```

`RowId` encodes a (pageIndex, slotIndex) pair. The encoded form is stable — a `RowId` written into the B+ tree at insert time remains valid indefinitely because rows are never physically removed or shifted.

### Catalog file

`catalog.cat` is a human-readable text file in the data directory:

```
TABLE users
COLUMN id INT
COLUMN name STRING
COLUMN age INT
INDEX idx_users_id users id
```

The `INDEX` line format is `INDEX <indexName> <tableName> <columnName>`. The index name is used in `EXPLAIN` output and survives restarts.

It is always rewritten atomically: written to `catalog.cat.tmp`, then `Files.move(..., ATOMIC_MOVE)`. On startup, the file is parsed to reconstruct `Catalog` state before any user query runs.

### WAL file

`wal.log` is an append-only binary file in the data directory. Each `WalEntry` is serialised as:

```
[1 byte]   type      (INSERT=1, UPDATE=2, DELETE=3, COMMIT=4, CHECKPOINT=5)
[variable] payload   (table name, row data, depending on type)
```

---

## Index lifecycle

1. **CREATE INDEX**: validates table/column/type/uniqueness, registers `IndexMetadata` (including index name) in the catalog, creates a `BPlusTree` in `IndexManager`, then backfills by scanning all live rows.
2. **Startup**: `Executor.rebuildIndexes()` reads all `IndexMetadata` from the catalog, creates `BPlusTree` instances, and scans each `.tbl` file to populate them. This takes O(N) time proportional to data size.
3. **INSERT**: after the row is appended to the table file, each indexed column's `BPlusTree` is updated.
4. **DELETE**: `IndexManager.delete(table, col, key)` removes the key from the tree. The slot is tombstoned in the page but not removed.
5. **Restart**: the index is rebuilt fresh from the table file; the in-memory tree is discarded.

The B+ tree (order 128) stores `Integer → RowId` mappings. Leaf nodes are doubly-linked for O(k) range traversal after an O(log N) initial descent.

---

## WAL lifecycle

```
Normal operation:
  Mutation request
      → append WalEntry (INSERT/UPDATE/DELETE)    [intent durable]
      → write to TableFile                        [data durable]
      → append WalEntry (COMMIT)                  [transaction confirmed]

Crash between intent and COMMIT:
  On next startup, RecoveryManager reads WAL.
  Entries without a matching COMMIT are discarded (no redo needed).
  Data already written to the table file is ignored (overwritten on next insert to same slot).

Clean startup:
  RecoveryManager confirms all committed entries are already durable.
  WAL is checkpointed (file truncated to zero).
```

---

## Startup / recovery lifecycle

```
Database.open(dataDir)
  │
  ├─ Catalog.load(catalog.cat)     — reconstruct table schemas + index metadata
  │
  ├─ StorageEngine.open()          — open TableFile handles for each known table
  │
  ├─ RecoveryManager.recover()
  │    ├─ Read wal.log
  │    ├─ Find all entries with matching COMMIT
  │    ├─ Confirm their data is already in the table files (redo is a no-op)
  │    └─ Checkpoint: truncate wal.log
  │
  └─ IndexManager.rebuildAll()     — scan each .tbl file, insert live rows into BPlusTrees
```

After startup completes the database is ready to accept queries.
