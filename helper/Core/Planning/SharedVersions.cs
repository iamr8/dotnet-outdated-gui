using NuGet.Versioning;
using NuGetExtended.Core.Model;

namespace NuGetExtended.Core.Planning;

/// Package ids that share one version site (e.g. `Version="$(LibVersion)"` on several items). A
/// target that one of the other ids never published would break their restore, so the scan blocks
/// that row up front. [EditPlanner.Plan] still skips it too: it also sees projects the scan did not.
public sealed class SharedVersions
{
    private readonly Dictionary<string, List<EditPlanner.Consumer>> _consumers;

    public SharedVersions(IReadOnlyList<EvaluatedProject> all) => _consumers = EditPlanner.BuildSiteMap(all).Consumers;

    /// Why (tfm, id) cannot move to [target], naming every other id on its site that has no
    /// published [target]. Null when nothing blocks it - including when [published] does not know
    /// an id's versions (not fetched): only a known gap blocks.
    public string? Blocker(EvaluatedTfm tfm, string id, NuGetVersion target,
        Func<string, EvaluatedTfm, string, IReadOnlyCollection<NuGetVersion>?> published)
    {
        var (site, _, transitive) = SiteResolver.Resolve(tfm, id);
        if (site == null || transitive || !_consumers.TryGetValue(SiteResolver.Key(site), out var users)) return null;

        var missing = users
            .Where(c => !string.Equals(c.Id, id, StringComparison.OrdinalIgnoreCase))
            .Where(c => published(c.Project, c.Tfm, c.Id) is { } versions && !versions.Contains(target))
            .Select(c => c.Id)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(x => x, StringComparer.OrdinalIgnoreCase)
            .ToList();
        if (missing.Count == 0) return null;

        var names = missing.Count == 1 ? missing[0] : string.Join(", ", missing.Take(missing.Count - 1)) + " and " + missing[^1];
        var shared = site.Kind == "property" ? $"$({site.Name}) in {Path.GetFileName(site.File)}" : $"one version in {Path.GetFileName(site.File)}";
        var has = missing.Count == 1 ? "has" : "have";
        return $"{id} shares {shared} with {names}. {names} {has} no version {target.ToNormalizedString()}, " +
               "so the update would break their restore. This update is blocked.";
    }
}
