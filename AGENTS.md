# AGENTS.md — dotnet outdated GUI

Context, conventions, and rules for anyone (human or AI) working in this repo.

## What this is

A **JetBrains Rider plugin** that wraps the [`dotnet-outdated`](https://github.com/dotnet-outdated/dotnet-outdated)
CLI in a tool window: list NuGet packages of the open solution's projects, check for updates,
and upgrade in place.

- Repo: https://github.com/iamr8/dotnet-outdated-gui
- Plugin id: `com.github.iamr8.dotnetoutdated` · name: **dotnet outdated GUI**
- Base package / Gradle group: `com.github.iamr8`

## Toolchain & build

- **Kotlin**, IntelliJ Platform Gradle Plugin `2.11.0`, target **Rider 2026.1 (build 261)**.
- **Kotlin plugin version must match Rider's bundled Kotlin metadata** — `2.3.0` for build 261.
  Older compilers fail with "incompatible version of Kotlin".
- Platform dependency is **local Rider** (`/Applications/Rider.app`) when present, else
  `rider("2026.1.4")` is downloaded (CI).
- **JDK**: platform-261 bytecode needs `--release 21`. The build runs on **JDK 22** via
  `org.gradle.java.home` in `gradle.properties` (machine-specific path). A plain `java` of 11 is
  too old to launch Gradle. In CI, `-Dorg.gradle.java.home="$JAVA_HOME"` overrides it (setup-java 21).
- **Version**: single source of truth is the **`VERSION`** file (no extension); `build.gradle.kts`
  reads it into the plugin version. Keep it at **0.1.0 until the first Marketplace publish**.
- `until-build` is intentionally unset (forward IDE compatibility / Marketplace-friendly).

### Common commands

```bash
export JAVA_HOME=<jdk-22-home>
./gradlew test                                   # unit tests (pure logic)
./gradlew verifyPluginStructure buildPlugin       # validate + package -> build/distributions/*.zip
./gradlew runIde                                  # sandbox Rider to drive the UI
# install a local build into the real Rider for manual testing:
rm -rf "$HOME/Library/Application Support/JetBrains/Rider2026.1/plugins/dotnet-outdated-rider"
unzip -q build/distributions/dotnet-outdated-rider-0.1.0.zip -d "$HOME/Library/Application Support/JetBrains/Rider2026.1/plugins"
```

To bake the Sentry DSN into a local build, `export SENTRY_DSN=<dsn>` before building.

## Architecture

```
cli/     OutdatedCommand / ListPackagesCommand (pure arg builders), DotnetOutdatedRunner (process),
         SolutionModel (.sln/.slnx parse), DotnetLocator
model/   OutdatedReport / ListPackagesReport (Gson DTOs), Severity (severity -> color)
parse/   Gson JSON -> model
settings/ OutdatedOptions (persisted), OutdatedOptionsService, OutdatedConfigurable (Settings page)
ui/      OutdatedToolWindowFactory, OutdatedPanel (toolbar + phases), PackageListView (grouped list),
         DotnetProjectNotificationProvider (editor banner)
root:    PluginErrorReportSubmitter + SentryReporter (opt-in error reporting)
```

### Key behaviors

- **Two phases**: Phase 1 = `dotnet list package` (offline, fast, gated by the "List all packages"
  option — OFF by default because it's heavy). Phase 2 = `dotnet outdated` ("Check for Updates").
- **Scan scope**: whole solution in one call when all projects selected; per-project (parallel,
  2–8 threads) for a subset OR when the solution has unsupported project types (`.shproj`) that
  `dotnet list package` can't load (`dotnet outdated` tolerates them, so it keeps the single call).
  Hard-fail of the whole-solution call falls back to per-project.
- **Grouped list**: `ProjectName · framework` header (grayed) per project+TFM; package rows show a
  checkbox + `Name · Current` (left) and the new version (right, whole-value colored by severity).
  Severity follows NuGet/SemVer: green=patch, yellow=minor, red=major/pre-release.
- **Checkboxes** (only outdated rows checkable) + Space toggles selection; **speed search** by name.
- Errors go to the IDE error reporter; "Report" sends to Sentry (opt-in only).

### Gotchas

- `dotnet outdated` JSON keys are **PascalCase** (`Projects`, `ResolvedVersion`, `UpgradeSeverity`);
  `dotnet list package --format json` keys are **camelCase**. Different DTOs.
- `-inc` (include filter) is a case-insensitive **substring** match — upgrading `Foo` may also hit
  `Foo.Bar`. The UI always re-scans after an upgrade to show the true state.
- `dotnet list package` **requires restore**; unrestored projects error (surfaced, skipped).

## Testing policy

Every functional change needs a test where practical. Pure logic (command builders, parsing,
severity, solution parsing, options round-trip) is unit-tested (JUnit4). UI is verified via
`runIde` / a local install. Run `./gradlew test` before committing.

## CI / release

- Workflows: `build.yml` (test + verify + buildPlugin + artifact), `codeql.yml` (security;
  CodeQL needs a real compile — `clean --no-daemon --no-build-cache`), `compatibility.yml`
  (weekly plugin verifier, pinned to a released Rider — `recommended()` can resolve 404 EAPs),
  `release.yml`, plus Dependabot. Actions are pinned to latest majors.
- **Release**: `release.yml` (Actions → Release, enter a version, or push a tag `v*`) bumps
  `VERSION`, tags, builds, creates a GitHub Release with the zip, and publishes to Marketplace if
  `PUBLISH_TOKEN` is set. Pushing to `main` never releases.
- **Secrets**: `SENTRY_DSN` (set; runtime error DSN), `PUBLISH_TOKEN` (add after the first manual
  Marketplace upload + approval).
- Sentry DSN is injected at build time from `SENTRY_DSN` into `sentry.properties` — **never
  committed**. The DSN is a write-only client key.

## Conventions & rules

- **Commits**: Conventional Commits; end the message with
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Update `CHANGELOG.md` under
  `[Unreleased]` for user-facing changes.
- **Git identity** in this repo: `iamr8` / `arash.shabbeh@gmail.com`. Push auth uses gh
  (repo-local credential helper `!gh auth git-credential`), not the machine keychain.
- **Never commit secrets/tokens.** DSN is injected, tokens live in env / GitHub secrets.
- Keep logic pure and small; put testable code where it can be unit-tested.
- License: **MIT** (`LICENSE`).
```
