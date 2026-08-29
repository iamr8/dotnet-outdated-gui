# Changelog

All notable changes to **dotnet outdated GUI** are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/iamr8/dotnet-outdated-gui/compare/v0.1.2...HEAD
[0.1.2]: https://github.com/iamr8/dotnet-outdated-gui/compare/v0.1.1...v0.1.2
[0.1.1]: https://github.com/iamr8/dotnet-outdated-gui/compare/v0.1.0...v0.1.1
[0.1.0]: https://github.com/iamr8/dotnet-outdated-gui/releases/tag/v0.1.0
