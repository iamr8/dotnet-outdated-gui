# Changelog

All notable changes to **NuGet (Extended)** (formerly *dotnet outdated GUI*) are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0]

### Changed
- **Renamed to NuGet (Extended).** The tool window, settings page and notifications use the new
  name. Your saved tool window place and notification settings reset once.
- **No `dotnet-outdated` tool needed.** The plugin ships its own engine. It uses your .NET SDK's
  MSBuild and NuGet (the .NET 6 SDK or later), with your NuGet.config, package source mapping and
  credential providers.
- **Upgrades keep version ranges.** `[12.0.1,14.0.0)` becomes `[13.0.4,14.0.0)`, not a fixed
  `13.0.4`. The new version is the newest one inside the range. A version above the
  upper bound is never offered, and is hidden unless **Show versions outside the range** is on.
- **Exact package upgrades.** Upgrading `Foo` no longer also upgrades `Foo.Bar`.
- **Maximum version** never goes above a range's upper bound.
- **Faster checks.** One engine per solution keeps project data between scans and uses NuGet's HTTP
  cache.

### Added
- Versions in properties (`Version="$(PollyVersion)"`): the property is updated, and other packages
  that share it are listed before anything changes.
- An upgrade plan before any change: files, shared versions, skipped packages. One Undo reverts all
  files.
- Floating versions (`2.*`) update through a restore, with no file edit.
- Out-of-date projects are restored before the scan. Turn it off with **Never run dotnet restore**.
- A project opened in safe mode (not trusted) is never restored and its file-based apps are not
  read. Out-of-date projects in it are listed as skipped.

### Fixed
- Upper-only ranges like `(,3.0.0]` no longer fail the scan.
- Cancel during a scan no longer leaves the toolbar disabled.
- EAP builds are now `0.2.0-eap.*`, so they sort above the released 0.1.4.
- The plugin no longer restores at the same time as Rider's own NuGet restore. Both wrote the same
  obj files, and Rider's restore failed with "…nuget.g.props already exists". The plugin now waits
  for Rider, and restores only what Rider did not.
- **Pre-release label** now keeps only pre-releases with that label (`rc` matches `rc.1`).
- **Maximum version** accepts a single number (`8` means any 8.x), and the settings page rejects
  text it can't read instead of ignoring it.
- **Include file-based apps** works on its own; it no longer needs "Find projects in subfolders".

## [0.1.4] - 2026-08-29

### Fixed
- **Much faster update check on large solutions.** The check now runs `dotnet outdated` once over
  the whole solution (as the CLI does), unless you narrow the **Scope** picker to a subset. Before,
  if any single project failed to restore (e.g. an `NU1102` version that doesn't exist — common
  mid-migration), the plugin silently re-ran every project one at a time, turning a ~15s scan into
  a minutes-long crawl. A whole-solution failure now surfaces the CLI's own error (which names the
  broken project) instead.

### Added
- Toolbar **Select All / Deselect All** button — checks every outdated package in one click, or
  clears them all. The label and icon follow the current state.
- A **checkbox on each project header** — toggles every outdated package under that project. It
  shows a three-state view (all / partial / none) and stays in sync with the toolbar button and
  the individual package checkboxes.
- **Speed-search match highlighting** — as you type to filter the list, the matched characters in
  the package name and project header are now highlighted, like Rider's own lists.

## [0.1.3] - 2026-07-26

### Fixed
- Failures coming from your solution — an unresolvable package version (`NU1102`), an unrestored
  project, a missing CLI — no longer raise the IDE's "Report error" dialog. They are shown as a
  notification with a short, actionable message and a **Copy Details** action for the full CLI
  output, and are logged as warnings instead of errors.

### Changed
- Error reporting now goes through the JetBrains Marketplace Exception Analyzer (the platform's
  own reporter) instead of Sentry, so reporting a plugin exception no longer prompts about
  certificates or third-party network access. Sentry, its dependency, and the baked-in DSN are gone.
- Unresolvable package versions get a dedicated message pointing at the project file /
  `Directory.Packages.props` instead of a wall of MSBuild output.

## [0.1.2] - 2026-07-18

### Changed
- Rewrote the plugin/Marketplace description to lead with Central Package Management and NuGet
  version-range/floating handling, and to summarize the list/severity/multi-select/search features.

## [0.1.1] - 2026-07-18

### Added
- Support for older Rider builds — compatible with Rider 2024.3 (build 243) and newer.
- Opt-in error reporting to Sentry (only when the user clicks "Report" in the IDE error dialog);
  uses an isolated client that doesn't touch the IDE's own error handling.

### Changed
- Group the package list by project and target framework, with a `ProjectName · netX` section
  header per group (matching the `dotnet outdated` CLI), instead of a single flat list.
- Color the entire new-version value by severity (no longer per-character portion).
- Per-row checkboxes (multi-select + Space to toggle) to choose packages to update; grayed
  project headers; type-to-search (speed search) by package/project name.

### Fixed
- Upgrade no longer passes scan/source flags (notably `-ifs`/`--ignore-failed-sources`) to
  `dotnet outdated -u` — those are forwarded to a nested restore that rejects them and failed
  every upgrade. Upgrade now sends only version-policy + timeout flags.

## [0.1.0] - 2026-07-17

### Added
- Tool window that lists NuGet packages of the open solution's projects with their current version.
- **Check for Updates** runs `dotnet outdated` to show available updates.
- Rider NuGet-style list view: `Name · CurrentVersion` on the left, new version right-aligned.
- Severity coloring of the new version (green = patch, yellow = minor, red = major / pre-release).
- In-place upgrade of selected packages via `dotnet outdated -u`.
- **Scope** picker over the open solution's projects.
- Settings page (Settings | Tools | dotnet outdated GUI) exposing every `dotnet outdated` argument,
  persisted per project. "List all packages" toggle (off by default).
- CLI presence check with an install prompt linking to the dotnet-outdated repository.
- Editor banner suggesting the tool when a `.csproj`/`Directory.Packages.props` file is opened.
- Errors routed to the IDE error reporter.

[0.2.0]: https://github.com/iamr8/nuget-extended/compare/v0.1.4...v0.2.0
[0.1.4]: https://github.com/iamr8/nuget-extended/compare/v0.1.3...v0.1.4
[0.1.3]: https://github.com/iamr8/nuget-extended/compare/v0.1.2...v0.1.3
[0.1.2]: https://github.com/iamr8/nuget-extended/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/iamr8/nuget-extended/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/iamr8/nuget-extended/releases/tag/v0.1.0
