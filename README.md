# DatabaseAPI

PLACEHOLDER — to be written in Task 10 of the implementation plan
(`MineActivity/docs/superpowers/plans/2026-06-01-databaseapi.md`).

Hardened HikariCP/MySQL layer for MineSide plugins: a pooled `Database` with TLS
validation + idempotent schema helpers, and five easy synchronous query helpers
(`query` / `queryFirst` / `update` / `batch` / `tx`). Platform-independent
(pure JDBC / HikariCP / slf4j) — works on Paper and Velocity.

Usage, JitPack coordinates and the `v1.0.0` tag are added in Task 10.
