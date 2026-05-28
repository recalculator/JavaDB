-- JavaDB demo script
-- Demonstrates DDL, DML, indexed queries, range scans, EXPLAIN, and deletes.

-- Create a table
CREATE TABLE users (id INT, name STRING, age INT);

-- Insert some rows
INSERT INTO users VALUES (1, 'Alice', 30);
INSERT INTO users VALUES (2, 'Bob', 25);
INSERT INTO users VALUES (3, 'Carol', 28);
INSERT INTO users VALUES (4, 'Dave', 35);
INSERT INTO users VALUES (5, 'Eve', 22);

-- Full table scan (no index yet)
SELECT * FROM users;

-- EXPLAIN before CREATE INDEX — shows FULL_SCAN
EXPLAIN SELECT * FROM users WHERE id = 3;

-- Create a B+ tree index on the id column
CREATE INDEX idx_users_id ON users(id);

-- EXPLAIN after CREATE INDEX — now shows INDEX_SCAN with index name
EXPLAIN SELECT * FROM users WHERE id = 3;
EXPLAIN SELECT * FROM users WHERE id BETWEEN 2 AND 4;
EXPLAIN SELECT * FROM users WHERE age = 30;

-- Point lookup via B+ tree index
SELECT * FROM users WHERE id = 3;

-- Range query via B+ tree range scan
SELECT * FROM users WHERE id > 2;

-- BETWEEN range scan
SELECT * FROM users WHERE id BETWEEN 2 AND 4;

-- Column projection
SELECT name, age FROM users WHERE id <= 3;

-- Delete a row and verify it is gone
DELETE FROM users WHERE id = 2;
SELECT * FROM users;

-- Update a row
UPDATE users SET age = 31 WHERE id = 1;
SELECT * FROM users WHERE id = 1;

exit
