using NuGet.Common;
using NuGet.LibraryModel;
using NuGet.ProjectModel;
using NuGet.Versioning;

namespace NuGetExtended.Helper.Projects;

public sealed record AssetPackage(string Id, string? RequestedRange, NuGetVersion? Resolved, bool Direct, int Depth, bool AutoReferenced);

public sealed record AssetsData(IReadOnlyList<AssetPackage> Packages, IReadOnlyDictionary<string, VersionRange?> DirectRanges, bool HasRuntimeTarget);

public static class AssetsReader
{
    internal static LockFile? Load(string assetsFile)
    {
        if (string.IsNullOrEmpty(assetsFile) || !File.Exists(assetsFile)) return null;
        var lockFile = LockFileUtilities.GetLockFile(assetsFile, NullLogger.Instance);
        return lockFile?.PackageSpec == null ? null : lockFile;
    }

    public static IReadOnlyDictionary<string, AssetsData?> ReadAll(string assetsFile, IEnumerable<string> tfmAliases, string runtime)
    {
        var lockFile = Load(assetsFile);
        return tfmAliases.Distinct(StringComparer.OrdinalIgnoreCase)
            .ToDictionary(a => a, a => lockFile == null ? null : Read(lockFile, a, runtime), StringComparer.OrdinalIgnoreCase);
    }

    public static AssetsData? Read(string assetsFile, string tfmAlias, string runtime) =>
        Load(assetsFile) is { } lockFile ? Read(lockFile, tfmAlias, runtime) : null;

    /// One target framework's view of an already parsed assets file ([Load] makes it with a non-null PackageSpec).
    internal static AssetsData? Read(LockFile lockFile, string tfmAlias, string runtime)
    {
        var tfi = lockFile.PackageSpec.TargetFrameworks.FirstOrDefault(t => string.Equals(t.TargetAlias, tfmAlias, StringComparison.OrdinalIgnoreCase))
                  ?? lockFile.PackageSpec.TargetFrameworks.FirstOrDefault(t => string.Equals(t.FrameworkName.GetShortFolderName(), tfmAlias, StringComparison.OrdinalIgnoreCase))
                  // Only a project with no TFM alias takes the single entry as is: with an alias, a
                  // different framework (net8.0 -> net9.0) means the file is from an older restore.
                  ?? (string.IsNullOrEmpty(tfmAlias) && lockFile.PackageSpec.TargetFrameworks.Count == 1 ? lockFile.PackageSpec.TargetFrameworks[0] : null);
        if (tfi == null) return null;

        var rid = string.IsNullOrWhiteSpace(runtime) ? null : runtime.Trim();
        var ridTarget = rid == null ? null : lockFile.GetTarget(tfi.FrameworkName, rid);
        var target = ridTarget ?? lockFile.GetTarget(tfi.FrameworkName, null);
        var resolved = new Dictionary<string, LockFileTargetLibrary>(StringComparer.OrdinalIgnoreCase);
        foreach (var lib in target?.Libraries ?? new List<LockFileTargetLibrary>())
            if (lib.Type == "package" && lib.Name != null) resolved[lib.Name] = lib;

        var directDeps = PackageDependencies(tfi).ToList();

        var result = new List<AssetPackage>();
        var depth = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase);
        var queue = new Queue<string>();
        foreach (var dep in directDeps)
        {
            var id = dep.Name;
            resolved.TryGetValue(id, out var lib);
            result.Add(new AssetPackage(id, dep.LibraryRange.VersionRange?.ToString(), lib?.Version, true, 0, dep.AutoReferenced));
            depth[id] = 0;
            queue.Enqueue(id);
        }

        while (queue.Count > 0)
        {
            var id = queue.Dequeue();
            if (!resolved.TryGetValue(id, out var lib)) continue;
            foreach (var child in lib.Dependencies)
            {
                if (depth.ContainsKey(child.Id) || !resolved.TryGetValue(child.Id, out var childLib)) continue;
                depth[child.Id] = depth[id] + 1;
                result.Add(new AssetPackage(child.Id, child.VersionRange?.ToString(), childLib.Version, false, depth[child.Id], false));
                queue.Enqueue(child.Id);
            }
        }

        var ranges = directDeps
            .GroupBy(d => d.Name, StringComparer.OrdinalIgnoreCase)
            .ToDictionary(g => g.Key, VersionRange? (g) => g.First().LibraryRange.VersionRange, StringComparer.OrdinalIgnoreCase);
        return new AssetsData(result, ranges, rid == null || ridTarget != null);
    }

    /// Dependencies' return type differs by SDK (IList on 6.x/8.x, ImmutableArray on 9.x/10.x);
    /// reading it by name instead of the compiled signature works for either shape.
    private static IEnumerable<LibraryDependency> PackageDependencies(TargetFrameworkInformation tfi)
    {
        var value = typeof(TargetFrameworkInformation).GetProperty(nameof(TargetFrameworkInformation.Dependencies))!.GetValue(tfi);
        return ((System.Collections.IEnumerable)value!).Cast<LibraryDependency>()
            .Where(d => d.LibraryRange.TypeConstraint.HasFlag(LibraryDependencyTarget.Package));
    }
}
