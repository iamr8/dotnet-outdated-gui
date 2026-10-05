using System.Text.Json;
using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class FileAppsTests
{
    [Fact]
    public void ScansFileBasedAppOnSdk10()
    {
        // file-based apps need SDK 10; a pinned older SDK can't even restore "app.cs" (MSB4025),
        // so skip before that instead of after - the sdkVersion check below runs too late for it.
        if (FixtureSolution.PinnedSdkMajor is < 10) return;
        var dir = FixtureSolution.NewDir();
        var feed = FixtureFeed.Create(dir, ("Humanizer.Core", new[] { "2.14.1", "3.0.10" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", feed));
        // PublishAot=false: a file-based app defaults to AOT-ready, which auto-references the SDK's
        // ILCompiler/ILLink build tools plus this machine's runtime packs on restore - packages the
        // fixture feed (deliberately offline, only fakes the one package under test) cannot supply.
        // Directives.Parse ignores `#:property` lines, so this does not add a package row.
        FixtureSolution.Write(dir, "app.cs", "#:property PublishAot=false\n#:package Humanizer.Core@2.14.1\nSystem.Console.WriteLine(1);\n");
        FixtureSolution.Restore(Path.Combine(dir, "app.cs"));
        using var h = HelperProcess.Start(dir);
        var sdk = Version.Parse(h.HelloLine.GetProperty("data").GetProperty("sdkVersion").GetString()!.Split('-')[0]);
        if (sdk.Major < 10) return; // file-based apps need SDK 10

        var r = h.Request("scan", new { solutionDir = dir, projects = new[] { Path.Combine(dir, "app.cs") }, options = new { includeFileBasedApps = true } })
            .GetProperty("result");

        var row = r.GetProperty("projects")[0].GetProperty("frameworks")[0].GetProperty("packages")[0];
        Assert.Equal("3.0.10", row.GetProperty("target").GetString());
    }

    [Fact]
    public void NonZeroExitIsAShortErrorNotAnException()
    {
        var dir = FixtureSolution.NewDir();
        // A nested global.json pinning an SDK that is not installed, rollForward disabled: `dotnet
        // build` itself cannot start (no compatible SDK), so FileBasedApps.Evaluate's non-zero-exit
        // branch runs for real - no restore, no network involved.
        FixtureSolution.Write(dir, "Old/global.json", @"{ ""sdk"": { ""version"": ""1.0.0"", ""rollForward"": ""disable"" } }");
        FixtureSolution.Write(dir, "Old/app.cs", "#:package Humanizer.Core@2.14.1\nConsole.WriteLine(1);\n");
        using var h = HelperProcess.Start(dir);
        var sdk = Version.Parse(h.HelloLine.GetProperty("data").GetProperty("sdkVersion").GetString()!.Split('-')[0]);
        if (sdk.Major < 10) return; // file-based apps need SDK 10

        var p = h.Request("evaluate", new { path = Path.Combine(dir, "Old/app.cs"), runtime = "" }).GetProperty("result");

        var error = p.GetProperty("error").GetString();
        Assert.False(string.IsNullOrWhiteSpace(error));
        Assert.DoesNotContain('\n', error!);
        Assert.True(error!.Length < 200);
        Assert.Empty(p.GetProperty("frameworks").EnumerateArray());
    }

    // Break: no catch around FileBasedApps.Evaluate, so an I/O error from Process.Start escapes as a bug.
    [Fact]
    public void ProcessStartFailureIsAProjectErrorNotABug()
    {
        var dir = FixtureSolution.NewDir();
        using var h = HelperProcess.Start(dir);
        var sdk = Version.Parse(h.HelloLine.GetProperty("data").GetProperty("sdkVersion").GetString()!.Split('-')[0]);
        if (sdk.Major < 10) return; // file-based apps need SDK 10

        // The app's folder does not exist, so Process.Start fails before dotnet runs.
        var r = h.Request("evaluate", new { path = Path.Combine(dir, "Missing", "app.cs"), runtime = "" });

        Assert.Equal(JsonValueKind.Null, r.GetProperty("error").ValueKind);
        var error = r.GetProperty("result").GetProperty("error").GetString();
        Assert.False(string.IsNullOrWhiteSpace(error));
        Assert.DoesNotContain('\n', error!);
    }
}
