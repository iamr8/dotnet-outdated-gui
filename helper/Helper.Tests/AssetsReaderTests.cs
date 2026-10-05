using NuGetExtended.Helper.Projects;
using Xunit;

namespace NuGetExtended.Helper.Tests;

/// AssetsReader runs in this process (see Helper.Tests.csproj) on a hand-written assets file.
/// It has two frameworks. net6.0 resolves Top 1.0.0. net8.0 resolves Top 1.1.0 and has one more direct package.
public class AssetsReaderTests
{
    private static string WriteAssets()
    {
        var dir = Fixtures.FixtureSolution.NewDir();
        var json = @"{
  'version': 3,
  'targets': {
    'net6.0': {
      'Top/1.0.0': { 'type': 'package', 'dependencies': { 'Leaf': '2.0.0' } },
      'Leaf/2.0.0': { 'type': 'package' }
    },
    'net8.0': {
      'Top/1.1.0': { 'type': 'package', 'dependencies': { 'Leaf': '2.1.0' } },
      'Leaf/2.1.0': { 'type': 'package' },
      'Extra/3.0.0': { 'type': 'package' }
    }
  },
  'libraries': {
    'Top/1.0.0': { 'sha512': 'x', 'type': 'package', 'path': 'top/1.0.0', 'files': [] },
    'Top/1.1.0': { 'sha512': 'x', 'type': 'package', 'path': 'top/1.1.0', 'files': [] },
    'Leaf/2.0.0': { 'sha512': 'x', 'type': 'package', 'path': 'leaf/2.0.0', 'files': [] },
    'Leaf/2.1.0': { 'sha512': 'x', 'type': 'package', 'path': 'leaf/2.1.0', 'files': [] },
    'Extra/3.0.0': { 'sha512': 'x', 'type': 'package', 'path': 'extra/3.0.0', 'files': [] }
  },
  'projectFileDependencyGroups': {
    'net6.0': [ 'Top >= 1.0.0' ],
    'net8.0': [ 'Extra >= 3.0.0', 'Top >= 1.0.0' ]
  },
  'packageFolders': { '@DIR@/.packages': {} },
  'project': {
    'version': '1.0.0',
    'restore': {
      'projectUniqueName': '@DIR@/P/P.csproj',
      'projectName': 'P',
      'projectPath': '@DIR@/P/P.csproj',
      'packagesPath': '@DIR@/.packages',
      'outputPath': '@DIR@/P/obj',
      'projectStyle': 'PackageReference',
      'originalTargetFrameworks': [ 'net6.0', 'net8.0' ],
      'sources': {},
      'frameworks': { 'net6.0': { 'targetAlias': 'net6.0', 'projectReferences': {} }, 'net8.0': { 'targetAlias': 'net8.0', 'projectReferences': {} } }
    },
    'frameworks': {
      'net6.0': { 'targetAlias': 'net6.0', 'dependencies': { 'Top': { 'target': 'Package', 'version': '[1.0.0, 2.0.0)' } } },
      'net8.0': { 'targetAlias': 'net8.0', 'dependencies': {
        'Top': { 'target': 'Package', 'version': '[1.0.0, 2.0.0)' },
        'Extra': { 'target': 'Package', 'version': '[3.0.0, )' } } }
    }
  }
}".Replace("@DIR@", dir.Replace('\\', '/')).Replace('\'', '"');
        var path = Path.Combine(dir, "P/obj/project.assets.json");
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, json);
        return path;
    }

    /// One comparable line per package plus the direct ranges, in a fixed order.
    private static string[] Describe(AssetsData? data) =>
        data == null
            ? Array.Empty<string>()
            : data.Packages.Select(p => $"{p.Id} {p.Resolved} direct={p.Direct} depth={p.Depth}")
                .Concat(data.DirectRanges.Select(r => $"range {r.Key} {r.Value}"))
                .Append($"runtime={data.HasRuntimeTarget}")
                .OrderBy(l => l, StringComparer.Ordinal).ToArray();

    // Break: Read(LockFile) goes back to the file, so each framework view needs its own parse.
    [Fact]
    public void FrameworkViewsNeedNoSecondRead()
    {
        var path = WriteAssets();
        var lockFile = AssetsReader.Load(path)!;
        File.Delete(path);

        Assert.Equal(new[]
        {
            "Leaf 2.0.0 direct=False depth=1",
            "Top 1.0.0 direct=True depth=0",
            "range Top [1.0.0, 2.0.0)",
            "runtime=True",
        }, Describe(AssetsReader.Read(lockFile, "net6.0", "")));
        Assert.Equal(new[]
        {
            "Extra 3.0.0 direct=True depth=0",
            "Leaf 2.1.0 direct=False depth=1",
            "Top 1.1.0 direct=True depth=0",
            "range Extra [3.0.0, )",
            "range Top [1.0.0, 2.0.0)",
            "runtime=True",
        }, Describe(AssetsReader.Read(lockFile, "net8.0", "")));
        Assert.Null(AssetsReader.Read(lockFile, "net7.0", "")); // not in the file: stale
    }

    // Break: ReadAll drops a framework, or gives one framework the view of another.
    [Fact]
    public void ReadAllEqualsOneReadPerFramework()
    {
        var path = WriteAssets();

        var all = AssetsReader.ReadAll(path, new[] { "net6.0", "net8.0", "NET8.0", "net7.0" }, "");

        Assert.Equal(3, all.Count); // "NET8.0" is the same framework as "net8.0"
        foreach (var alias in new[] { "net6.0", "net8.0", "net7.0" })
            Assert.Equal(Describe(AssetsReader.Read(path, alias, "")), Describe(all[alias]));
        Assert.Contains("Top 1.0.0 direct=True depth=0", Describe(all["net6.0"]));
        Assert.Contains("Top 1.1.0 direct=True depth=0", Describe(all["NET8.0"]));
        Assert.Null(all["net7.0"]);
    }

    // Break: ReadAll throws on a missing file, or leaves out the frameworks.
    [Fact]
    public void ReadAllOfAMissingFileIsNullForEveryFramework()
    {
        var all = AssetsReader.ReadAll(Path.Combine(Path.GetTempPath(), "hx-" + Guid.NewGuid(), "project.assets.json"), new[] { "net6.0", "net8.0" }, "");

        Assert.Equal(2, all.Count);
        Assert.All(all.Values, Assert.Null);
    }
}
