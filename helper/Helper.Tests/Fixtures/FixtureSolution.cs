using System.Diagnostics;

namespace NuGetExtended.Helper.Tests.Fixtures;

public static class FixtureSolution
{
    // The TFM fixtures build against. Defaults to net8.0 (today's behavior); CI overrides it per
    // SDK leg via HELPER_TEST_TFM, since SDK 6 cannot restore net8.0 (NETSDK1045).
    public static string Tfm { get; } =
        Environment.GetEnvironmentVariable("HELPER_TEST_TFM") is { Length: > 0 } tfm ? tfm : "net8.0";

    /// The major version of the SDK this run is pinned to (HELPER_TEST_SDK), or null when unpinned
    /// (the default installed SDK applies). Lets a test skip work the pinned SDK cannot do at all
    /// (e.g. restoring a file-based app) before it hits an SDK error instead of a real assertion.
    public static int? PinnedSdkMajor =>
        Environment.GetEnvironmentVariable("HELPER_TEST_SDK") is { Length: > 0 } v ? Version.Parse(v.Split('-')[0]).Major : null;

    // net6.0 has no Directory.CreateTempSubdirectory; build the same shape by hand.
    public static string NewDir()
    {
        var dir = Path.Combine(Path.GetTempPath(), "hx-" + Guid.NewGuid());
        Directory.CreateDirectory(dir);
        // CI pins the SDK under test via HELPER_TEST_SDK (the default SDK build tests run with can
        // differ from it); global.json in the fixture dir makes `dotnet restore`/build under it pick
        // that SDK. latestPatch: only the exact major.minor band matters, patch drift is fine.
        var sdk = Environment.GetEnvironmentVariable("HELPER_TEST_SDK");
        if (!string.IsNullOrEmpty(sdk))
            Write(dir, "global.json", $@"{{""sdk"":{{""version"":""{sdk}"",""rollForward"":""latestPatch""}}}}");
        return dir;
    }

    public static void Write(string dir, string relPath, string content)
    {
        var path = Path.Combine(dir, relPath);
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, content);
    }

    /// NuGet.config with <clear/> so only fixture sources are used. The package cache is private
    /// to the fixture so tests never touch the user's global packages folder.
    public static void WriteNuGetConfig(string dir, params (string Name, string Source)[] sources)
    {
        var adds = string.Concat(sources.Select(s => $"<add key=\"{s.Name}\" value=\"{s.Source}\" />"));
        Write(dir, "NuGet.config", $@"<?xml version=""1.0"" encoding=""utf-8""?>
<configuration>
  <config><add key=""globalPackagesFolder"" value=""{Path.Combine(dir, ".packages")}"" /></config>
  <packageSources><clear />{adds}</packageSources>
</configuration>");
    }

    public static void Restore(string projectOrDir)
    {
        // Run from the project folder: dotnet picks the SDK from the working directory's global.json.
        var workDir = Directory.Exists(projectOrDir) ? projectOrDir : Path.GetDirectoryName(Path.GetFullPath(projectOrDir))!;
        // A `.cs` file-based app (SDK 10+) needs a different way to disable node reuse: passing
        // -nodeReuse:false as an extra argument makes the CLI treat the whole command line as a
        // plain MSBuild invocation instead of a file-based app, so "app.cs" fails to parse as a
        // project file (MSB4025). MSBUILDDISABLENODEREUSE=1 (the classic MSBuild env var) disables
        // it the same way without that problem.
        var isFileApp = projectOrDir.EndsWith(".cs", StringComparison.OrdinalIgnoreCase);
        // -nodeReuse:false: reused MSBuild nodes can hold the output pipes open for their 15-min idle timeout.
        var psi = new ProcessStartInfo("dotnet", isFileApp
            ? $"restore \"{projectOrDir}\" -v q"
            : $"restore \"{projectOrDir}\" -v q -nodeReuse:false")
        {
            WorkingDirectory = workDir,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
        };
        ScrubMsBuildEnv(psi);
        if (isFileApp) psi.Environment["MSBUILDDISABLENODEREUSE"] = "1";
        using var p = Process.Start(psi)!;
        var output = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
        p.WaitForExit();
        if (p.ExitCode != 0) throw new InvalidOperationException("restore failed:\n" + output);
    }

    /// `dotnet test` exports MSBuild* variables (MSBuildExtensionsPath, MSBuildSDKsPath, ...) that point
    /// at its own SDK. A user's shell has none, so child processes start without them.
    public static void ScrubMsBuildEnv(ProcessStartInfo psi)
    {
        foreach (var key in psi.Environment.Keys.Where(k => k.StartsWith("MSBuild", StringComparison.OrdinalIgnoreCase)).ToList())
            psi.Environment.Remove(key);
    }
}
