using NuGet.Versioning;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Scanning;

namespace NuGetExtended.Helper.Projects;

/// Compares what the project asks for now with what the last restore used. No file times:
/// a no-op restore leaves the assets file as it was.
public static class RestoreState
{
    public static bool IsStale(EvaluatedProject project, string runtime) =>
        IsStale(project, t => AssetsReader.Read(t.AssetsFile, t.Framework, runtime));

    /// [read] lets the scan parse each assets file once and reuse it for the rows.
    public static bool IsStale(EvaluatedProject project, Func<EvaluatedTfm, AssetsData?> read)
    {
        if (project.Error != null || project.Frameworks.Count == 0) return false;
        foreach (var tfm in project.Frameworks)
        {
            var data = read(tfm);
            if (data == null || !data.HasRuntimeTarget) return true;
            var ids = tfm.Items.Where(i => i.ItemType == "PackageReference").Select(i => i.Id).Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            // The other way: a direct package of the last restore that the project no longer
            // references. Auto-referenced ones come from SDK targets, not from the project.
            // (GlobalPackageReference items become PackageReference items at evaluation.)
            var referenced = new HashSet<string>(ids, StringComparer.OrdinalIgnoreCase);
            if (data.Packages.Any(a => a.Direct && !a.AutoReferenced && !referenced.Contains(a.Id))) return true;
            foreach (var id in ids)
            {
                var text = RowBuilder.Requested(tfm, id);
                if (text == null) continue;
                if (!data.DirectRanges.TryGetValue(id, out var inAssets)) return true;
                if (inAssets == null || !VersionRange.TryParse(text, allowFloating: true, out var wanted)) continue;
                if (!wanted.Equals(inAssets)) return true;
            }
        }
        return false;
    }
}
