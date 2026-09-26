using NuGetExtended.Core.Model;

namespace NuGetExtended.Core.Planning;

public static class SiteResolver
{
    public static (ValueSite? Site, string? Problem, bool Transitive) Resolve(EvaluatedTfm tfm, string id)
    {
        bool Is(PackageItem i, string type) => i.ItemType == type && string.Equals(i.Id, id, StringComparison.OrdinalIgnoreCase);
        var reference = tfm.Items.FirstOrDefault(i => Is(i, "PackageReference"));
        var global = tfm.Items.FirstOrDefault(i => Is(i, "GlobalPackageReference"));
        if (reference == null && global == null) return (null, null, true);

        if (reference?.VersionOverride != null) return (reference.OverrideSite, reference.SiteProblem, false);
        if (tfm.CpmEnabled)
        {
            var central = tfm.Items.FirstOrDefault(i => Is(i, "PackageVersion")) ?? global;
            if (central != null) return (central.VersionSite, central.SiteProblem, false);
        }
        var item = reference ?? global!;
        return (item.VersionSite, item.SiteProblem ?? (item.VersionSite == null ? "no version is set for this package" : null), false);
    }

    public static string Key(ValueSite s) =>
        $"{s.File.ToLowerInvariant()}|{s.Kind}|{s.Name}|{s.Line}|{s.Column}";
}
