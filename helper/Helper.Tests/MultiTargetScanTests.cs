using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class MultiTargetScanTests
{
    // Break: a framework's rows come from another framework's view of the assets file.
    [Fact]
    public void EachFrameworkOfAProjectListsItsOwnResolvedVersion()
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        // Plural <TargetFrameworks> even when it collapses to one TFM (SDK 6): see ScanTests.
        var tfms = new[] { "net6.0", FixtureSolution.Tfm }.Distinct().ToList();
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFrameworks>{string.Join(";", tfms)}</TargetFrameworks></PropertyGroup>
  <ItemGroup>
    <PackageReference Include=""Polly"" Version=""7.0.0"" Condition=""'$(TargetFramework)' == 'net6.0'"" />
    <PackageReference Include=""Polly"" Version=""7.2.4"" Condition=""'$(TargetFramework)' != 'net6.0'"" />
  </ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project);
        using var h = HelperProcess.Start(dir);

        var r = h.Request("scan", new { solutionDir = dir, projects = new[] { project }, options = new { checkUpdates = false, includeUpToDate = true } }).GetProperty("result");

        Assert.Empty(r.GetProperty("stale").EnumerateArray());
        var resolved = r.GetProperty("projects").EnumerateArray().Single().GetProperty("frameworks").EnumerateArray()
            .ToDictionary(f => f.GetProperty("framework").GetString()!,
                f => f.GetProperty("packages").EnumerateArray().Single().GetProperty("resolved").GetString()!);
        Assert.Equal(tfms.ToDictionary(t => t, t => t == "net6.0" ? "7.0.0" : "7.2.4"), resolved);
    }
}
