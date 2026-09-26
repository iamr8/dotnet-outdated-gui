# NuGet (Extended) — Rider plugin

A JetBrains Rider tool window that adds what Rider's NuGet window does not do yet: solution-wide
update checks and upgrades that **keep your version ranges** (`[1.0.0,2.0.0)`, `(,3.0.0]`) and
work with **Central Package Management** (`Directory.Packages.props`). Formerly *dotnet outdated
GUI*. No extra tool to install: it uses your .NET SDK's own MSBuild and NuGet.

[![Build](https://img.shields.io/github/actions/workflow/status/iamr8/nuget-extended/build.yml?branch=main&style=flat-square&label=build)](https://github.com/iamr8/nuget-extended/actions/workflows/build.yml)
[![CodeQL](https://img.shields.io/github/actions/workflow/status/iamr8/nuget-extended/codeql.yml?branch=main&style=flat-square&label=codeql)](https://github.com/iamr8/nuget-extended/actions/workflows/codeql.yml)
[![Release](https://img.shields.io/github/v/release/iamr8/nuget-extended?style=flat-square)](https://github.com/iamr8/nuget-extended/releases)
[![Last commit](https://img.shields.io/github/last-commit/iamr8/nuget-extended?style=flat-square)](https://github.com/iamr8/nuget-extended/commits/main)
[![License: MIT](https://img.shields.io/github/license/iamr8/nuget-extended?style=flat-square)](LICENSE)
[![JetBrains Marketplace](https://img.shields.io/jetbrains/plugin/v/32989?style=flat-square&label=marketplace)](https://plugins.jetbrains.com/plugin/32989)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/32989?style=flat-square&label=downloads)](https://plugins.jetbrains.com/plugin/32989)
[![Rating](https://img.shields.io/jetbrains/plugin/r/rating/32989?style=flat-square)](https://plugins.jetbrains.com/plugin/32989/reviews)

## Screenshot

<img width="207" height="374" alt="Screenshot 2026-07-18 at 12 21 01 PM" src="https://github.com/user-attachments/assets/aa9f0b5c-3f42-4878-a71a-096f28ba71e0" />

## Features

- **Version ranges stay ranges** — `[12.0.1,14.0.0)` upgrades to `[13.0.4,14.0.0)`. The new
  version is the newest one inside the range. A version above the upper bound is never offered;
  turn on **Show versions outside the range** to see it (gray, not checkable).
- **Central Package Management** — updates the central `<PackageVersion>` (or a `VersionOverride`)
  and leaves the versionless `<PackageReference>` alone.
- **Versions in properties** — `Version="$(PollyVersion)"` updates the property; packages that share
  it are listed first.
- **Floating versions** (`3.*`) update through a restore, with no file edit.
- Packages per project / target framework (`ProjectName · netX` headers), colored by **NuGet /
  SemVer** severity — green = patch, yellow = minor, red = major / pre-release.
- **Checkboxes** (multi-select + <kbd>Space</kbd>), **speed search**, a **Scope** picker.
- An **upgrade plan** before any change; one Undo reverts every file.
- Uses your NuGet.config, package source mapping and credential providers (Azure Artifacts,
  GitHub Packages, …).
- Editor banner on `.csproj` / `Directory.Packages.props`; opt-in exception reporting straight to
  the JetBrains Marketplace (no third-party service).

## Install

From the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/32989):

1. In Rider, open **Settings → Plugins → Marketplace**.
2. Search for **NuGet (Extended)**.
3. Click **Install**, then restart the IDE when prompted.

Or grab a `.zip` from [Releases](https://github.com/iamr8/nuget-extended/releases) and install via
**Settings → Plugins → ⚙ → Install Plugin from Disk…**

> Embedding the JetBrains Marketplace card/install **widgets** (`mp-widget.js`) requires a page that
> runs JavaScript. GitHub strips `<script>` from READMEs, so they can't render here — use the
> Marketplace badge/link above on GitHub, and the widgets on your own site:
>
> ```html
> <div id="dog-card"></div>
> <script src="https://plugins.jetbrains.com/assets/scripts/mp-widget.js"></script>
> <script>MarketplaceWidget.setupMarketplaceWidget('card', 32989, "#dog-card");</script>
> ```

## Requirements

- JetBrains Rider 2024.3 or later.
- The .NET 6 SDK or later. The solution's `global.json` picks the SDK.

## Using it

1. Open the **NuGet (Extended)** tool window (right dock).
2. **Check for Updates** — finds newer versions for the projects in **Scope** and colors them by
   severity. Projects whose packages are out of date are restored first.
3. **Check** the packages you want (checkbox per row; multi-select + <kbd>Space</kbd>), then
   **Update Selected**. A dialog shows the plan: the files, other packages that share a version,
   and anything skipped (with the reason). **Upgrade** edits the files, restores, and checks again.
   **Undo** reverts the files.
4. A package already at the newest version inside its range is up to date, so it is not listed. Turn
   on **Show versions outside the range** to list it with the newer version (gray).
5. **Reload Packages** (↻) lists every package, up to date or not, with no update check. It needs
   **List all packages** in settings.

Problems in your solution (an unrestored project, a version that doesn't exist, a failing package
source) show as a **notification** with a short message and **Copy Details** for the full output.
They are not treated as plugin crashes. Real plugin exceptions go to the IDE's error reporter,
which submits them (only if you choose to) to this plugin's **Exceptions** page on the JetBrains
Marketplace.

### Settings (⚙ toolbar → Settings | Tools | NuGet (Extended))

Settings are saved per project (`.idea/nuget-extended.xml`):

- **Packages analyzed** — list all packages (off by default), versions outside the range (off by
  default), auto-referenced packages, transitive
  packages + depth (an upgrade adds a direct reference).
- **Version policy** — pre-release + label, version lock, maximum version (never above a range's
  upper bound), only versions older than N days.
- **Discovery** — projects in subfolders when no solution is open, file-based apps
  (`#:package`, .NET 10 SDK), show-only / hide name filters.
- **Sources & reliability** — never run `dotnet restore`, ignore failed sources, engine timeout,
  runtime identifier, NuGet credential log level.

## Building

The build needs a JDK (see `gradle.properties`) and the .NET SDK (6 or later) for the bundled
engine in `helper/`.

```bash
dotnet test helper/Core.Tests && dotnet test helper/Helper.Tests   # engine tests
./gradlew test          # plugin unit tests
./gradlew buildPlugin    # build/distributions/nuget-extended-rider-<version>.zip (engine included)
./gradlew runIde         # sandbox Rider with the plugin
```

Install the built zip via **Settings → Plugins → ⚙ → Install Plugin from Disk…**

## Layout

```
helper/   .NET engine (net6.0): MSBuild evaluation, assets, NuGet feeds, target selection, upgrade plan
engine/   helper process, JSON-lines client, file watcher
edit/     applies the engine's edits to IDE documents (one undo step)
cli/      restore runner, scan scope, solution parsing, dotnet locator, failure messages
model/    severity -> color
settings/ options, settings page
ui/       tool window, panel, grouped list, rows, confirm text
```
