# Maternote Studio

Studio is an independent Gradle Build. It must not depend on the main Android app,
its Room database, repositories, learner records, or managed asset paths.

The only shared runtime contract is the pure Kotlin `../package-format` composite
build. `.mnproj` is private editable project state; `.mnote` is the distribution
format consumed by Maternote.

Build from the repository root:

```shell
./gradlew -p studio test :app:assembleDebug
```
