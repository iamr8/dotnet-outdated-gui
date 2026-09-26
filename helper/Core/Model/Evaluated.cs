namespace NuGetExtended.Core.Model;

public sealed record ValueSite(
    string Kind,            // "metadata" | "property" | "directive"
    string File,
    int Line, int Column,   // 1-based hints (element location)
    string Name,            // metadata name ("Version"/"VersionOverride") or property name
    string Form,            // "attribute" | "child" | "property"
    string RawText,         // text to replace
    string? ItemType,       // owner item type (metadata only)
    string? IdentityAttr,   // "Include" | "Update" (metadata only)
    string? Identity,       // unevaluated Include/Update value (metadata only)
    string? Condition,      // owner element Condition
    string? GroupCondition); // parent group Condition

public sealed record PackageItem(
    string Id, string ItemType, string? Version, string? VersionOverride,
    ValueSite? VersionSite, ValueSite? OverrideSite, string? SiteProblem, bool PrivateAssetsAll);

public sealed record EvaluatedTfm(
    string Framework, string AssetsFile, bool CpmEnabled, bool TransitivePinning,
    string? CentralFile, IReadOnlyList<PackageItem> Items, IReadOnlyList<string> RestoreSources,
    string? PackagesRoot = null); // NuGetPackageRoot: the global packages folder

public sealed record EvaluatedProject(
    string Path, string Name, IReadOnlyList<EvaluatedTfm> Frameworks,
    IReadOnlyList<string> Inputs, string? Error);
