# dotnet outdated GUI — Rider plugin

A JetBrains Rider tool window for the [`dotnet-outdated`](https://github.com/dotnet-outdated/dotnet-outdated)
CLI: list the NuGet packages of the open solution's projects, check for updates (colored by
NuGet / SemVer severity), and upgrade the ones you pick — in place.

[![Build](https://img.shields.io/github/actions/workflow/status/iamr8/dotnet-outdated-gui/build.yml?branch=main&style=flat-square&label=build)](https://github.com/iamr8/dotnet-outdated-gui/actions/workflows/build.yml)
[![CodeQL](https://img.shields.io/github/actions/workflow/status/iamr8/dotnet-outdated-gui/codeql.yml?branch=main&style=flat-square&label=codeql)](https://github.com/iamr8/dotnet-outdated-gui/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/iamr8/dotnet-outdated-gui?style=flat-square)](https://github.com/iamr8/dotnet-outdated-gui/releases)
[![Last commit](https://img.shields.io/github/last-commit/iamr8/dotnet-outdated-gui?style=flat-square)](https://github.com/iamr8/dotnet-outdated-gui/commits/main)
[![License: MIT](https://img.shields.io/github/license/iamr8/dotnet-outdated-gui?style=flat-square)](LICENSE)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/com.github.iamr8.dotnetoutdated?style=flat-square&label=marketplace)](https://plugins.jetbrains.com/plugin/com.github.iamr8.dotnetoutdated)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/com.github.iamr8.dotnetoutdated?style=flat-square&label=downloads)](https://plugins.jetbrains.com/plugin/com.github.iamr8.dotnetoutdated)

<!-- The two JetBrains Marketplace badges activate once the plugin is published; if they render
     empty, replace `com.github.iamr8.dotnetoutdated` with the numeric Marketplace plugin id. -->

## Features

- Lists NuGet packages per project / target framework (`ProjectName · netX` section headers).
- Checks for updates via `dotnet outdated`; the new version is colored by **NuGet / SemVer**
  severity — green = patch, yellow = minor, red = major / pre-release.
- Per-row **checkboxes** (multi-select + <kbd>Space</kbd>) to pick packages, then upgrade in place.
- **Speed search** — start typing to filter by package / project name.
- **Scope** picker over the open solution's projects; parallel per-project scans; handles `.shproj`.
- **Settings** exposing every `dotnet outdated` argument (Settings | Tools | dotnet outdated GUI).
- Editor banner on `.csproj` / `Directory.Packages.props`; opt-in error reporting.

## Requirements

- JetBrains Rider 2026.1 (build 261).
- .NET SDK on `PATH`.
- The [`dotnet-outdated`](https://github.com/dotnet-outdated/dotnet-outdated) CLI — see its
  [installation instructions](https://github.com/dotnet-outdated/dotnet-outdated#installation).

## Using it

1. Open the **dotnet outdated GUI** tool window (right dock). The first time it's shown it lazily
   lists every package with its **current** version via `dotnet list package` — fast, offline,
   no update check. **New Version is empty** until you check for updates.
2. **Reload Packages** (↻) — re-list current packages (use after installing/removing a package
   so the list isn't stale).
3. **Scope** — the current open solution; check/uncheck which of its **loaded projects** to show.
4. **Check for Updates** — runs `dotnet outdated -utd` to fill **New Version**, and colors the
   whole outdated row by severity (**red** major/pre-release, **yellow** minor, **green** patch).
5. **Check** the packages you want (checkbox per row; multi-select rows + <kbd>Space</kbd> toggles
   them all), then **Update Selected** — runs `dotnet outdated -u -inc <pkg> …` and re-scans.
   Up-to-date packages can't be checked. Start typing to **speed-search** by package/project name.

Errors (missing tool, unrestored project, non-zero exit) are sent to the **IDE error reporter**
(selectable / copyable, with a "Copy Error Report to Clipboard" action), not the status bar.

New-version colors follow **NuGet / Semantic Versioning** semantics (the CLI legend):
**red** = major update or pre-release (possible breaking changes), **yellow** = minor
(backwards-compatible features), **green** = patch (backwards-compatible fixes); up-to-date
packages are uncolored.

### Settings (⚙ toolbar → Settings | Tools | dotnet outdated GUI)

Settings live in the standard JetBrains **Settings** dialog. **List all packages** is off by
default — it lists every package via `dotnet list package` and can be a massive operation on
large solutions; leave it off to work only with outdated packages. Every configurable
`dotnet outdated` argument is exposed and persisted per project (`.idea/nuget-extended.xml`):

- **Packages analyzed** — list up-to-date (`-utd`), auto-references (`-i`), transitive (`-t`) + depth (`-td`).
- **Version policy** — pre-release (`-pre`) + label (`-prl`), version lock (`-vl`), maximum version (`-mv`), older-than days (`-ot`).
- **Discovery** — recurse (`-r`), file-based apps (`-fba`), include/exclude name filters (`-inc`/`-exc`).
- **Sources & reliability** — no-restore (`-n`), ignore failed sources (`-ifs`), idle timeout (`-it`), runtime (`-rt`), NuGet credential log level (`-ncll`).

Safer-than-CLI defaults: `-utd` on, `-ifs` on, `-it 300` (CLI default 120). Flags are only passed
when they differ from the CLI default, keeping the invocation minimal.

> Note: `dotnet outdated`'s `-inc` filter matches package names by *substring*, so upgrading
> `Microsoft.EntityFrameworkCore` may also upgrade `…EntityFrameworkCore.Design`. The tree
> always re-scans after an upgrade to show the true resulting state.

## Building

The build runs on JDK 22 (`gradle.properties` pins `org.gradle.java.home`) and compiles against
the locally installed Rider at `/Applications/Rider.app`.

```bash
./gradlew test          # unit tests (pure logic: command builder, JSON parser, severity, target discovery)
./gradlew buildPlugin    # produces build/distributions/dotnet-outdated-rider-<version>.zip
./gradlew runIde         # launches a sandbox Rider with the plugin for manual testing
```

Install the built zip via **Settings → Plugins → ⚙ → Install Plugin from Disk…**

## Layout

```
cli/    OutdatedCommand (pure arg builders), DotnetOutdatedRunner (process), SolutionTargets, DotnetLocator
model/  OutdatedReport (JSON schema), Severity (severity → color legend)
parse/  OutdatedReportParser (JSON → model)
ui/     tool window factory, panel (toolbar + background scan/upgrade), tree table, row model
```
