namespace NuGetExtended.Core.Versions;

/// Every plugin option the engine uses. Names and defaults match the Kotlin OutdatedOptions.
public sealed record ScanOptions(
    bool IncludeAutoReferences = false,
    bool Transitive = false,
    int TransitiveDepth = 1,
    bool IncludeUpToDate = false,
    string PreRelease = "Auto",
    string PreReleaseLabel = "",
    string VersionLock = "None",
    string MaximumVersion = "",
    int OlderThanDays = 0,
    bool IgnoreFailedSources = true,
    string Runtime = "",
    string CredLogLevel = "Warning",
    bool IncludeFileBasedApps = false,
    bool CheckUpdates = true);
