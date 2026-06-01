package hu.mineside.database;

// PLACEHOLDER — implement across Tasks 5-8 (+ test seam in Task 9).
// MineActivity/docs/superpowers/plans/2026-06-01-databaseapi.md
// Main class (AutoCloseable). Built over Tasks:
//   T5: ctor (host/port/db validation, TLS posture logging, lazy hardened Hikari
//       pool, driver pin, prep-stmt cache), getConnection/dataSource/close.
//   T6: idempotent schema helpers ensureColumn/ensureColumnType/ensureIndex
//       (SAFE_IDENT/SAFE_COLUMN_EXPR guards).
//   T7: query/queryFirst/update (+ param-less overloads), param capture.
//   T8: batch (chunked, single transaction) + tx (rolls back on ANY exception).
//   T9: package-private forTesting(DataSource, debugParams) seam for the H2 IT.
// ds field typed as javax.sql.DataSource from the start. No code yet — scaffold only.
