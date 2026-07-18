# Contributing

Thanks for your interest in **dotnet outdated GUI**! Contributions are welcome.

## Prerequisites

- JetBrains Rider **2026.1** (build 261) installed at `/Applications/Rider.app` (the build
  compiles against the local install).
- A JDK capable of `--release 21` (the repo builds on JDK 22; JDK 21 also works).
- The [`dotnet-outdated`](https://github.com/dotnet-outdated/dotnet-outdated) CLI for manual
  testing — see its [installation instructions](https://github.com/dotnet-outdated/dotnet-outdated#installation).

## Build & test

```bash
./gradlew test          # unit tests (pure logic)
./gradlew buildPlugin    # produces build/distributions/*.zip
./gradlew runIde         # launches a sandbox Rider with the plugin
```

To try a local build in your own Rider: **Settings → Plugins → ⚙ → Install Plugin from Disk…**

## Project layout

```
cli/     command builders (pure), process runner, solution & dotnet discovery
model/   JSON DTOs and severity mapping (pure)
parse/   JSON → model (Gson)
settings/ options model, persistence service, Settings page
ui/      tool window, package list view, editor notification
```

## Guidelines

- Keep logic **pure and unit-tested** where possible (command building, parsing, severity,
  solution parsing). UI wiring is verified via `runIde`.
- Every functional change should come with a test. Run `./gradlew test` before opening a PR.
- Match the existing Kotlin style; keep files focused and small.
- Update `CHANGELOG.md` under `[Unreleased]` with a short note.

## Reporting issues

Use the issue templates. Include your Rider version, `dotnet --version`,
`dotnet outdated --version`, and steps to reproduce.

## License

By contributing you agree that your contributions are licensed under the repository
[LICENSE](LICENSE).
