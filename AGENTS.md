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

## Architecture

```
cli/     OutdatedCommand / ListPackagesCommand (pure arg builders), DotnetOutdatedRunner (process),
         SolutionModel (.sln/.slnx parse), DotnetLocator, CliFailures (CLI output -> user message)
model/   OutdatedReport / ListPackagesReport (Gson DTOs), Severity (severity -> color)
parse/   Gson JSON -> model
settings/ OutdatedOptions (persisted), OutdatedOptionsService, OutdatedConfigurable (Settings page)
ui/      OutdatedToolWindowFactory, OutdatedPanel (toolbar + phases), PackageListView (grouped list),
         DotnetProjectNotificationProvider (editor banner)
```

### Key behaviors

- **Core value prop (lead with this in all user-facing copy)**: works with **Central Package
  Management** (`Directory.Packages.props` — upgrades the central `<PackageVersion>`, leaves the
  versionless `<PackageReference>` intact) and **NuGet version ranges / floating versions**
  (`[1.0.0,2.0.0)`, `(,3.0.0]`, `3.*` — surfaced as the CLI's resolved concrete version, not raw
  brackets). Both are handled by the underlying CLIs; we surface + upgrade correctly.
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
- **Error routing** (two distinct classes, never mixed):
  - *User/environment failures* (missing CLI, unrestored project, `NU1102` version that doesn't
    exist, non-zero dotnet exit) → notification balloon via the `dotnet outdated GUI`
    `<notificationGroup>` + `LOG.warn`. The short message comes from `CliFailures.describe`;
    the raw CLI output is behind the balloon's **Copy Details** action. Never `LOG.error` —
    that opens the IDE fatal-error dialog and would fill the Marketplace Exceptions tab with
    other people's broken solutions.
  - *Plugin bugs* (unexpected `Throwable` in a background task) → `LOG.error`, i.e. the IDE error
    reporter, which submits to the **JetBrains Marketplace Exception Analyzer**
    (`<errorHandler implementation="com.intellij.diagnostic.JetBrainsMarketplaceErrorReportSubmitter"/>`,
    platform-provided since 2023.3). Reports land on the plugin's *Exceptions* tab
    (`plugins.jetbrains.com/plugin/32989/edit/exception-analyzer`) and only for Marketplace-installed
    builds — the Report action is absent/inert in `runIde` and local-zip installs.

### Gotchas

- `dotnet outdated` JSON keys are **PascalCase** (`Projects`, `ResolvedVersion`, `UpgradeSeverity`);
  `dotnet list package --format json` keys are **camelCase**. Different DTOs.
- `-inc` (include filter) is a case-insensitive **substring** match — upgrading `Foo` may also hit
  `Foo.Bar`. The UI always re-scans after an upgrade to show the true state.
- `dotnet list package` **requires restore**; unrestored projects error (surfaced, skipped).

## Testing policy

Every functional change needs a test where practical. Pure logic (command builders, parsing,
severity, solution parsing, options round-trip) is unit-tested (JUnit4).

**Verification happens in CI, not locally.** Don't run Gradle locally to prove a change works —
open the PR and let its checks do it. `build.yml` runs, on every PR: `test`,
`verifyPluginProjectConfiguration`, `verifyPluginStructure`, `buildPlugin`, and the **Plugin
Verifier against the current Rider** (`verifyPlugin -PverifierIdes=current`). A red check is the
signal to fix; a green one is the evidence. The full IDE range still runs in `compatibility.yml`.
UI behavior that no check can cover is confirmed by installing the built zip in real Rider.

## CI / release

- Workflows: `build.yml` (test + verify + buildPlugin + **Plugin Verifier on the current Rider** +
  artifact), `codeql.yml` (security;
  CodeQL needs a real compile — `clean --no-daemon --no-build-cache`), `compatibility.yml`
  (weekly plugin verifier, pinned to released Riders across the range — 2024.3.6 / 2025.2.4 /
  2026.1.4 / 2026.2; `recommended()` can resolve 404 EAPs),
  `release.yml`, plus Dependabot. Actions are pinned to latest majors.
- **EAP dev builds**: every successful `build.yml` run on **`main`** publishes an **EAP GitHub
  pre-release** (the `eap` job) — NOT the Marketplace. The plugin version is date + build number
  (`0.0.0-eap.<yyyyMMdd>.<run>`, overriding `VERSION` via `-PpluginVersion`); its `0.0.0` head
  keeps it below any real release in the IDE's version comparison (the `-eap` suffix is not what
  demotes it). The tag is `eap-<yyyyMMdd>.<run>`. The release notes name the target
  version (the `VERSION` file / milestone) and list the PRs merged since the last stable `v*` tag.
  For local testing: download the zip, install via Settings → Plugins → ⚙ → Install from Disk.
- **Release model**: branch-based.
  - `main` = development; `build.yml` builds + verifies + publishes an EAP pre-release (above).
    It never publishes to the Marketplace.
  - To release: bump `VERSION` **in the same PR**, then merge that PR into the **`release`**
    branch. `release.yml` gates on the version — if `VERSION` > the last released `v*` tag it
    tags `v<VERSION>`, builds, creates a GitHub Release, and publishes to the Marketplace
    (when `PUBLISH_TOKEN` is set). If `VERSION` is identical to or lower than the last tag, it
    **skips** (no tag/release/publish). Keep `main` and `release` in sync after a release.
- **Secrets**: `PUBLISH_TOKEN` (Marketplace publish), `SYNC_PAT` (release→main sync PR).
  No error-reporting secret: exceptions go to the Marketplace Exception Analyzer, which needs none.

## Conventions & rules

- **Commits**: Conventional Commits; end the message with
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Update `CHANGELOG.md` under
  `[Unreleased]` for user-facing changes.
- **Git identity** in this repo: `iamr8` / `arash.shabbeh@gmail.com`. Push auth uses gh
  (repo-local credential helper `!gh auth git-credential`), not the machine keychain.
- **Never commit secrets/tokens.** DSN is injected, tokens live in env / GitHub secrets.
- Keep logic pure and small; put testable code where it can be unit-tested.
- License: **MIT** (`LICENSE`).

### Pull requests

Every PR must be enriched — not just a title:

- **Body** follows [`.github/pull_request_template.md`](.github/pull_request_template.md):
  *Summary* (what & why, `Closes #NN`), *Changes*, *Testing*, *Checklist*, screenshots for UI.
- **Assignee** set (normally the author, e.g. `iamr8`).
- At least one **label**: `bug`, `enhancement`, `documentation`, `ci`, or `dependencies`
  (create a fitting one if none applies).
- **Base branch**: `main` for development; a **release** PR targets the `release` branch and
  includes the `VERSION` bump (see the Release model above).
- Keep it focused — one concern per PR; update `CHANGELOG.md` under `[Unreleased]` for
  user-facing changes.
```
