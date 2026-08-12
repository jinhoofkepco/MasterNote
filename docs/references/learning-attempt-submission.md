# Learning, attempt, and submission references

The learning vertical keeps Room entities private and exposes repository models. UI code never
receives a DAO or Room entity. No upstream implementation source is copied.

## Architecture Samples

- Revision: `ee66e1526b84c026615df032c705842b7d2a521f`
- Inspected: `DefaultTaskRepository.kt` and `DefaultTaskRepositoryTest.kt`
- Adopted: one repository entry point, Flow observations, injected dispatcher/test doubles
- Rejected: network synchronization, Task-specific mapping, fire-and-forget writes

## Now in Android

- Revision: `7d45eae4f8720a0c77f507712ba2437ff974b6ed`
- Inspected: `NiaDatabase.kt` and `DatabaseMigrations.kt`
- Adopted: explicit database version, exported schemas, centralized migration registration,
  package-private DAO ownership
- Difference: MasterNote declares a v1-to-v2 auto-migration because the revision only adds tables;
  `MigrationTestHelper` still verifies schema validation and old annotation-row preservation.

## AndroidX Room

Room 2.8.4 remains pinned. Submission uses one `withTransaction` boundary across submission
creation, immutable stroke references, answer snapshotting, and Attempt status transition.
`MigrationTestHelper` creates the previous exported schema and validates migration with retained
annotation data.
