# Content build boundaries

```text
Main Android app ──────┐
                      ├──> package-format (pure Kotlin/JVM)
Independent Studio ───┘
```

The main application and Studio are independent Gradle Builds. Neither can
depend on the other. Studio cannot access the main Room database, learner
profiles, attempts, submissions, reviews, remote sessions, repositories, or
managed asset paths. Their only shared runtime contract is `.mnote` through the
pure Kotlin `package-format` build.

## Builds

- Root build: Android Reader, persistence, library, and importer.
- `package-format`: model, JSON codec, semantic/ZIP validator, fixtures, CLI.
- `studio`: `.mnproj` editing state, editor/provider boundaries, preview, and
  deterministic validated export.

`.mnproj` is editable private Studio state. `.mnote` contains one immutable book
revision and never contains learner records. Imported assets remain in the
content-addressed Managed Asset Store; virtual folder moves change only Room
relationships.

## CI isolation

`package-format.yml`, `studio.yml`, and the existing main-app workflow execute
separately. `contract-compatibility.yml` is the explicit bridge: Studio exporter
tests run against the shared strict validator, then the main importer Android
test source is compiled against the same contract.
