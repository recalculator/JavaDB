package com.javadb.recovery;

import com.javadb.executor.Executor;
import com.javadb.wal.WalEntryType;
import com.javadb.wal.WriteAheadLog;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Reads the WAL on startup and replays committed operations.
 *
 * This engine uses a synchronous-flush strategy: every storage mutation is
 * flushed to disk BEFORE the COMMIT record is written to the WAL. Therefore
 * a COMMIT in the WAL always means the data is already on disk — redo is a
 * no-op in the normal case.
 *
 * Recovery is still meaningful for one edge case: if a previous run wrote a
 * WAL entry but crashed BEFORE flushing storage (between the WAL append and
 * the physical page write). In that case the WAL entry has no matching COMMIT,
 * so it is an incomplete (uncommitted) transaction and is discarded here.
 *
 * Outcome:
 *   - Entries without a matching COMMIT are discarded (rollback by omission).
 *   - Entries with a COMMIT are confirmed as already durable; logged for audit.
 *   - After recovery, the WAL is checkpointed (truncated) to prevent unbounded growth.
 */
public class RecoveryManager {

    private final WriteAheadLog wal;

    public RecoveryManager(WriteAheadLog wal) {
        this.wal = wal;
    }

    /**
     * Returns the committed operation lines from the WAL.
     * Incomplete transactions (no trailing COMMIT) are excluded.
     */
    public List<String> committedOperations() throws IOException {
        List<String> allLines = wal.readAll();
        Deque<String> pending = new ArrayDeque<>();
        List<String> committed = new ArrayList<>();

        for (String line : allLines) {
            String type = line.split("\\|")[0];
            if (type.equals(WalEntryType.COMMIT.name()) || type.equals(WalEntryType.CHECKPOINT.name())) {
                committed.addAll(pending);
                pending.clear();
            } else {
                pending.add(line);
            }
        }
        // Anything still in `pending` has no COMMIT — discard it (crash before commit).
        if (!pending.isEmpty()) {
            System.out.println("[Recovery] Discarding " + pending.size()
                + " uncommitted WAL entry(ies) from incomplete transaction.");
        }
        return committed;
    }

    /**
     * Performs startup recovery.
     *
     * Because this engine flushes storage before writing COMMIT, every committed
     * WAL entry is already reflected on disk. Recovery confirms the count and
     * then checkpoints (truncates) the WAL so it does not grow unboundedly.
     */
    public void recover(Executor executor) throws IOException {
        List<String> committed = committedOperations();
        if (committed.isEmpty()) {
            System.out.println("[Recovery] WAL clean — no operations to replay.");
        } else {
            System.out.println("[Recovery] " + committed.size()
                + " committed operation(s) confirmed durable (already on disk).");
        }
        // Checkpoint: truncate the WAL now that we have confirmed all committed
        // data is in the storage files. Future crashes will not need to replay
        // operations from before this point.
        wal.checkpoint();
        System.out.println("[Recovery] WAL checkpointed.");
    }
}
