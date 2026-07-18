# Changelog

All notable changes to **dotnet outdated GUI** are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- Upgrade no longer passes scan/source flags (notably `-ifs`/`--ignore-failed-sources`) to
  `dotnet outdated -u` — those are forwarded to a nested restore that rejects them and failed
  every upgrade. Upgrade now sends only version-policy + timeout flags.

### Added
- Opt-in error reporting to Sentry (only when the user clicks "Report" in the IDE error dialog);
  uses an isolated client that doesn't touch the IDE's own error handling.

### Changed
- Group the package list by project and target framework, with a `ProjectName · netX` section
  header per group (matching the `dotnet outdated` CLI), instead of a single flat list.
- Color the entire new-version value by severity (no longer per-character portion).
- Per-row checkboxes (multi-select + Space to toggle) to choose packages to update; grayed
  project headers; type-to-search (speed search) by package/project name.

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

[Unreleased]: https://github.com/iamr8/dotnet-outdated-gui/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/iamr8/dotnet-outdated-gui/releases/tag/v0.1.0
