using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class AssetsTests
{
    private static string Setup()
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir,
            new FixtureFeed.Package("Top", "1.0.0", Array.Empty<string>()),
            new FixtureFeed.Package("Leaf", "2.0.0", Array.Empty<string>()));
        // Make Top depend on Leaf: rewrite Top's nuspec with a dependency.
        File.Delete(Path.Combine(feed, "top.1.0.0.nupkg"));
        using (var zip = System.IO.Compression.ZipFile.Open(Path.Combine(feed, "top.1.0.0.nupkg"), System.IO.Compression.ZipArchiveMode.Create))
        using (var w = new StreamWriter(zip.CreateEntry("Top.nuspec").Open()))
            w.Write(@"<?xml version=""1.0""?><package xmlns=""http://schemas.microsoft.com/packaging/2013/05/nuspec.xsd""><metadata><id>Top</id><version>1.0.0</version><authors>f</authors><description>f</description><dependencies><dependency id=""Leaf"" version=""2.0.0"" /></dependencies></metadata></package>");
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "Directory.Packages.props", @"<Project>
  <PropertyGroup><ManagePackageVersionsCentrally>true</ManagePackageVersionsCentrally></PropertyGroup>
  <ItemGroup><PackageVersion Include=""Top"" Version=""[1.0.0,2.0.0)"" /></ItemGroup>
</Project>");
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Top"" /></ItemGroup>
</Project>");
        FixtureSolution.Restore(Path.Combine(dir, "P/P.csproj"));
        return dir;
    }

    [Fact]
    public void ReadsDirectAndTransitiveWithDepth()
    {
        var dir = Setup();
        using var h = HelperProcess.Start(dir);

        var r = h.Request("assets", new { path = Path.Combine(dir, "P/P.csproj") }).GetProperty("result");

        Assert.False(r.GetProperty("stale").GetBoolean());
        var pkgs = r.GetProperty("packages").EnumerateArray().ToDictionary(p => p.GetProperty("id").GetString()!);
        Assert.True(pkgs["Top"].GetProperty("direct").GetBoolean());
        Assert.Equal("1.0.0", pkgs["Top"].GetProperty("resolved").GetString());
        Assert.Equal("[1.0.0, 2.0.0)", pkgs["Top"].GetProperty("requestedRange").GetString());
        Assert.False(pkgs["Leaf"].GetProperty("direct").GetBoolean());
        Assert.Equal(1, pkgs["Leaf"].GetProperty("depth").GetInt32());
    }

    [Fact]
    public void ChangedCentralVersionIsStale()
    {
        var dir = Setup();
        var props = Path.Combine(dir, "Directory.Packages.props");
        File.WriteAllText(props, File.ReadAllText(props).Replace("[1.0.0,2.0.0)", "[1.0.0,3.0.0)"));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("assets", new { path = Path.Combine(dir, "P/P.csproj") }).GetProperty("result");

        Assert.True(r.GetProperty("stale").GetBoolean());
    }

    [Fact]
    public void RemovedReferenceIsStale()
    {
        // The last restore still lists Top as direct; the project no longer asks for it. The
        // central PackageVersion alone does not count as a reference.
        var dir = Setup();
        var project = Path.Combine(dir, "P/P.csproj");
        File.WriteAllText(project, File.ReadAllText(project).Replace(@"<PackageReference Include=""Top"" />", ""));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("assets", new { path = project }).GetProperty("result");

        Assert.True(r.GetProperty("stale").GetBoolean());
    }

    [Fact]
    public void ChangedTargetFrameworkIsStale()
    {
        // The assets file has one TFM, but it is not the one the project now asks for. Both TFMs
        // bring no implicit package, so only the TFM itself differs.
        var dir = Setup();
        var project = Path.Combine(dir, "P/P.csproj");
        var other = FixtureSolution.Tfm == "net6.0" ? "net5.0" : "net6.0";
        File.WriteAllText(project, File.ReadAllText(project).Replace($"<TargetFramework>{FixtureSolution.Tfm}</TargetFramework>", $"<TargetFramework>{other}</TargetFramework>"));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("assets", new { path = project }).GetProperty("result");

        Assert.True(r.GetProperty("stale").GetBoolean());
    }

    [Fact]
    public void TouchedFilesWithSameVersionsAreNotStale()
    {
        // A no-op restore keeps the old assets file, so file times must not decide.
        var dir = Setup();
        File.SetLastWriteTimeUtc(Path.Combine(dir, "Directory.Packages.props"), DateTime.UtcNow.AddMinutes(5));
        File.SetLastWriteTimeUtc(Path.Combine(dir, "P/P.csproj"), DateTime.UtcNow.AddMinutes(5));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("assets", new { path = Path.Combine(dir, "P/P.csproj") }).GetProperty("result");

        Assert.False(r.GetProperty("stale").GetBoolean());
    }

    [Fact]
    public void MissingAssetsIsStale()
    {
        var dir = Setup();
        Directory.Delete(Path.Combine(dir, "P/obj"), true);
        using var h = HelperProcess.Start(dir);

        var r = h.Request("assets", new { path = Path.Combine(dir, "P/P.csproj") }).GetProperty("result");

        Assert.True(r.GetProperty("stale").GetBoolean());
    }
}
