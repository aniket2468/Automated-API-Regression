CREATE TABLE specifications (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    payload CLOB NOT NULL
);
CREATE INDEX idx_spec_name ON specifications(name, created_at);
CREATE TABLE suites (
    id VARCHAR(36) PRIMARY KEY,
    spec_id VARCHAR(36) NOT NULL REFERENCES specifications(id),
    created_at VARCHAR(40) NOT NULL,
    payload CLOB NOT NULL
);
CREATE TABLE runs (
    id VARCHAR(36) PRIMARY KEY,
    suite_id VARCHAR(36) NOT NULL REFERENCES suites(id),
    status VARCHAR(20) NOT NULL,
    created_at VARCHAR(40) NOT NULL,
    payload CLOB NOT NULL
);
CREATE INDEX idx_runs_suite ON runs(suite_id, created_at);
CREATE TABLE test_results (
    run_id VARCHAR(36) NOT NULL REFERENCES runs(id),
    test_id VARCHAR(80) NOT NULL,
    payload CLOB NOT NULL,
    PRIMARY KEY (run_id, test_id)
);
