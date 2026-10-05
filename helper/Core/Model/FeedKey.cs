namespace NuGetExtended.Core.Model;

/// Which feed context a package is looked up in: the project's folder (its NuGet.config), its restore
/// sources, and the package id. Scan groups by this key, and planUpgrade reads candidates by it, so a
/// consumer is always checked against the feeds of its own project.
public static class FeedKey
{
    /// The restore sources of [t]: what [EvaluatedTfm.RestoreSources] and [EvaluatedTfm.AdditionalSources] hold.
    public static string Sources(EvaluatedTfm t) =>
        string.Join(";", t.RestoreSources) + "|" + string.Join(";", t.AdditionalSources ?? Array.Empty<string>());

    public static string Of(string projectPath, EvaluatedTfm t, string id) =>
        Path.GetDirectoryName(projectPath) + "|" + Sources(t) + "|" + id.ToLowerInvariant();
}
