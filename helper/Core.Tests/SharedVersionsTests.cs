using NuGet.Versioning;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Planning;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class SharedVersionsTests
{
    private const string Root = "/repo";
    private static readonly NuGetVersion Target = NuGetVersion.Parse("5.0.2");

    private static ValueSite Prop(string name, string raw) =>
        new("property", $"{Root}/Directory.Packages.props", 2, 5, name, "property", raw, null, null, null, null, null);

    private static ValueSite Meta(string id, string raw) =>
        new("metadata", $"{Root}/A/A.csproj", 4, 5, "Version", "attribute", raw, "PackageReference", "Include", id, null, null);

    private static PackageItem Ref(string id, ValueSite site) => new(id, "PackageReference", site.RawText, null, site, null, null, false);

    private static EvaluatedProject Proj(string name, params PackageItem[] items) =>
        new($"{Root}/{name}/{name}.csproj", name, new[] { new EvaluatedTfm("net8.0", "", false, false, null, items, Array.Empty<string>()) },
            new[] { $"{Root}/{name}/{name}.csproj" }, null);

    private static Func<string, EvaluatedTfm, string, IReadOnlyCollection<NuGetVersion>?> Published(params (string Id, string[] Versions)[] ids) =>
        (_, _, id) => ids.Where(x => x.Id == id).Select(x => (IReadOnlyCollection<NuGetVersion>)x.Versions.Select(NuGetVersion.Parse).ToList()).FirstOrDefault();

    // Break: Blocker ignores the other ids on the site (always null).
    [Fact]
    public void OtherIdWithoutTargetBlocks()
    {
        var site = Prop("LibVersion", "5.0.1");
        var a = Proj("A", Ref("Lib.X", site), Ref("Lib.Y", site));

        var blocker = new SharedVersions(new[] { a }).Blocker(a.Frameworks[0], "Lib.X", Target,
            Published(("Lib.X", new[] { "5.0.1", "5.0.2" }), ("Lib.Y", new[] { "5.0.1" })));

        Assert.NotNull(blocker);
        Assert.Contains("Lib.Y", blocker);
        Assert.Contains("$(LibVersion)", blocker);
    }

    // Break: Blocker blocks without checking whether the target is published for the other id.
    [Fact]
    public void OtherIdWithTargetDoesNotBlock()
    {
        var site = Prop("LibVersion", "5.0.1");
        var a = Proj("A", Ref("Lib.X", site), Ref("Lib.Y", site));

        var blocker = new SharedVersions(new[] { a }).Blocker(a.Frameworks[0], "Lib.X", Target,
            Published(("Lib.X", new[] { "5.0.1", "5.0.2" }), ("Lib.Y", new[] { "5.0.1", "5.0.2" })));

        Assert.Null(blocker);
    }

    // Break: unknown versions (no data fetched) are treated as "missing".
    [Fact]
    public void UnknownVersionsDoNotBlock()
    {
        var site = Prop("LibVersion", "5.0.1");
        var a = Proj("A", Ref("Lib.X", site), Ref("Lib.Y", site));

        var blocker = new SharedVersions(new[] { a }).Blocker(a.Frameworks[0], "Lib.X", Target,
            Published(("Lib.X", new[] { "5.0.1", "5.0.2" })));

        Assert.Null(blocker);
    }

    // Break: the row's own id is checked as another consumer (e.g. a second project on another feed).
    [Fact]
    public void SameIdInOtherProjectDoesNotBlock()
    {
        var site = Prop("LibVersion", "5.0.1");
        var a = Proj("A", Ref("Lib.X", site));
        var b = Proj("B", Ref("Lib.X", site));

        var blocker = new SharedVersions(new[] { a, b }).Blocker(a.Frameworks[0], "Lib.X", Target,
            (path, _, _) => path == b.Path ? new[] { NuGetVersion.Parse("5.0.1") } : new[] { Target });

        Assert.Null(blocker);
    }

    // Break: consumers of other sites are mixed in (keyed by id, not by site).
    [Fact]
    public void IdOnOwnSiteDoesNotBlock()
    {
        var a = Proj("A", Ref("Lib.X", Meta("Lib.X", "5.0.1")), Ref("Lib.Y", Meta("Lib.Y", "5.0.1") with { Line = 5 }));

        var blocker = new SharedVersions(new[] { a }).Blocker(a.Frameworks[0], "Lib.X", Target,
            Published(("Lib.X", new[] { "5.0.2" }), ("Lib.Y", new[] { "5.0.1" })));

        Assert.Null(blocker);
    }

    // Break: only the first missing id is reported.
    [Fact]
    public void EveryMissingIdIsNamed()
    {
        var site = Prop("LibVersion", "5.0.1");
        var a = Proj("A", Ref("Lib.X", site), Ref("Lib.Y", site), Ref("Lib.Z", site));

        var blocker = new SharedVersions(new[] { a }).Blocker(a.Frameworks[0], "Lib.X", Target,
            Published(("Lib.X", new[] { "5.0.2" }), ("Lib.Y", new[] { "5.0.1" }), ("Lib.Z", new[] { "5.0.1" })));

        Assert.NotNull(blocker);
        Assert.Contains("Lib.Y", blocker);
        Assert.Contains("Lib.Z", blocker);
    }
}
