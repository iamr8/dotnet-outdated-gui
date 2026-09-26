using System.Text.Json;
using System.Net;
using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class PlanTests
{
    [Fact]
    public void PlansCentralRangeEditWithExactLocation()
    {
        var dir = FixtureSolution.NewDir();
        Directory.CreateDirectory(Path.Combine(dir, ".git"));
        var feed = FixtureFeed.Create(dir, ("Newtonsoft.Json", new[] { "12.0.1", "13.0.4", "14.0.1" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "Directory.Packages.props", @"<Project>
  <PropertyGroup><ManagePackageVersionsCentrally>true</ManagePackageVersionsCentrally></PropertyGroup>
  <ItemGroup>
    <PackageVersion Include=""Newtonsoft.Json"" Version=""[12.0.1,14.0.0)"" />
  </ItemGroup>
</Project>");
        FixtureSolution.Write(dir, "A/A.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Newtonsoft.Json"" /></ItemGroup>
</Project>");
        var a = Path.Combine(dir, "A/A.csproj");
        FixtureSolution.Restore(a);
        using var h = HelperProcess.Start(dir);

        var plan = h.Request("planUpgrade", new
        {
            solutionDir = dir,
            allProjects = new[] { a },
            rows = new[] { new { project = a, framework = FixtureSolution.Tfm, id = "Newtonsoft.Json", target = "13.0.4" } },
            options = new { },
        }).GetProperty("result");

        var edit = plan.GetProperty("edits")[0];
        Assert.EndsWith("Directory.Packages.props", edit.GetProperty("file").GetString());
        Assert.Equal("[13.0.4,14.0.0)", edit.GetProperty("value").GetString());
        Assert.Equal("[12.0.1,14.0.0)", edit.GetProperty("expected").GetString());
        Assert.Equal(4, edit.GetProperty("line").GetInt32());
        Assert.Equal("PackageVersion", edit.GetProperty("itemType").GetString());
    }

    [Fact]
    public void TwoSpellingsOfOneProjectPathAreOneProject()
    {
        var dir = FixtureSolution.NewDir();
        Directory.CreateDirectory(Path.Combine(dir, ".git"));
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "A/A.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""7.0.0"" /></ItemGroup>
</Project>");
        var a = Path.Combine(dir, "A/A.csproj");
        FixtureSolution.Restore(a);
        using var h = HelperProcess.Start(dir);

        var r = h.Request("planUpgrade", new
        {
            solutionDir = dir,
            allProjects = new[] { a, Path.Combine(dir, "A/../A/A.csproj") },
            rows = new[] { new { project = a, framework = FixtureSolution.Tfm, id = "Polly", target = "7.2.4" } },
            options = new { },
        });

        Assert.Equal(JsonValueKind.Null, r.GetProperty("error").ValueKind);
        var edit = Assert.Single(r.GetProperty("result").GetProperty("edits").EnumerateArray());
        Assert.Equal("7.2.4", edit.GetProperty("value").GetString());
    }

    [Fact]
    public void TwoSpellingsOfOneProjectPathAreOneProjectInAScan()
    {
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "A/A.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""7.0.0"" /></ItemGroup>
</Project>");
        var a = Path.Combine(dir, "A/A.csproj");
        FixtureSolution.Restore(a);
        using var h = HelperProcess.Start(dir);

        var r = h.Request("scan", new { solutionDir = dir, projects = new[] { a, Path.Combine(dir, "A/../A/A.csproj") }, options = new { } });

        var project = Assert.Single(r.GetProperty("result").GetProperty("projects").EnumerateArray());
        Assert.Single(project.GetProperty("frameworks")[0].GetProperty("packages").EnumerateArray());
    }

    [Fact]
    public void PlansPropertyEditUsingConsumerProjectContextForKnownVersions()
    {
        var dir = FixtureSolution.NewDir();
        Directory.CreateDirectory(Path.Combine(dir, ".git"));
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0", "7.2.4", "8.8.0" }));
        // No source in NuGet.config at all: the only way to reach the feed is through the
        // project's own RestoreAdditionalProjectSources (parsed into EvaluatedTfm.RestoreSources).
        // If the handler used the solution dir with no sources instead of a consumer project's
        // FeedContext, GetCandidatesAsync would come back empty and the plan would find no known
        // version for the property site, so this proves which context was actually used.
        FixtureSolution.WriteNuGetConfig(dir);
        FixtureSolution.Write(dir, "Directory.Build.props", @"<Project>
  <PropertyGroup><PollyVer>7.0.0</PollyVer></PropertyGroup>
</Project>");
        FixtureSolution.Write(dir, "A/A.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup>
    <TargetFramework>{FixtureSolution.Tfm}</TargetFramework>
    <RestoreAdditionalProjectSources>{feed}</RestoreAdditionalProjectSources>
  </PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""$(PollyVer)"" /></ItemGroup>
</Project>");
        var a = Path.Combine(dir, "A/A.csproj");
        FixtureSolution.Restore(a);
        using var h = HelperProcess.Start(dir);

        var plan = h.Request("planUpgrade", new
        {
            solutionDir = dir,
            allProjects = new[] { a },
            rows = new[] { new { project = a, framework = FixtureSolution.Tfm, id = "Polly", target = "7.2.4" } },
            options = new { },
        }).GetProperty("result");

        var edit = Assert.Single(plan.GetProperty("edits").EnumerateArray());
        Assert.EndsWith("Directory.Build.props", edit.GetProperty("file").GetString());
        Assert.Equal("property", edit.GetProperty("target").GetString());
        Assert.Equal("7.2.4", edit.GetProperty("value").GetString());
    }

    [Fact]
    public void FailedSourceExplainsAPropertySiteSkipInsteadOfTheGenericReason()
    {
        using var listener = new HttpListener();
        var port = FreePort();
        listener.Prefixes.Add($"http://127.0.0.1:{port}/");
        listener.Start();
        _ = Task.Run(async () =>
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try { ctx = await listener.GetContextAsync(); } catch { break; }
                ctx.Response.StatusCode = 401;
                ctx.Response.Close();
            }
        });

        var dir = FixtureSolution.NewDir();
        Directory.CreateDirectory(Path.Combine(dir, ".git"));
        // 7.2.4 exists nowhere in the working feed: the target would be rejected regardless, but the
        // failed source still needs to be the reason reported, not the generic fallback text.
        var feed = FixtureFeed.Create(dir, ("Polly", new[] { "7.0.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        FixtureSolution.Write(dir, "Directory.Build.props", @"<Project>
  <PropertyGroup><PollyVer>7.0.0</PollyVer></PropertyGroup>
</Project>");
        FixtureSolution.Write(dir, "A/A.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""$(PollyVer)"" /></ItemGroup>
</Project>");
        var a = Path.Combine(dir, "A/A.csproj");
        FixtureSolution.Restore(a); // against the local feed only, so restore succeeds

        // The 401 source is added after restore, same as ScanTests.FailedSourceMarksRowInsteadOfHidingIt.
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed), ("private", $"http://127.0.0.1:{port}/v3/index.json"));
        using var h = HelperProcess.Start(dir);

        var plan = h.Request("planUpgrade", new
        {
            solutionDir = dir,
            allProjects = new[] { a },
            rows = new[] { new { project = a, framework = FixtureSolution.Tfm, id = "Polly", target = "7.2.4" } },
            options = new { },
        }).GetProperty("result");

        var skip = Assert.Single(plan.GetProperty("skipped").EnumerateArray());
        Assert.Contains("sign-in needed", skip.GetProperty("reason").GetString());
    }

    private static int FreePort()
    {
        var l = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
        l.Start();
        var port = ((IPEndPoint)l.LocalEndpoint).Port;
        l.Stop();
        return port;
    }

    [Fact]
    public void MalformedRowIsAUserErrorNotABug()
    {
        var dir = FixtureSolution.NewDir();
        using var h = HelperProcess.Start(dir);

        var r = h.Request("planUpgrade", new
        {
            solutionDir = dir,
            allProjects = Array.Empty<string>(),
            rows = new object?[] { null },
            options = new { },
        });

        Assert.Equal("user", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void NullAllProjectsEntryIsAUserErrorNotABug()
    {
        var dir = FixtureSolution.NewDir();
        using var h = HelperProcess.Start(dir);

        var r = h.Request("planUpgrade", new
        {
            solutionDir = dir,
            allProjects = new string?[] { null },
            rows = Array.Empty<object>(),
            options = new { },
        });

        Assert.Equal("user", r.GetProperty("error").GetProperty("kind").GetString());
    }
}
