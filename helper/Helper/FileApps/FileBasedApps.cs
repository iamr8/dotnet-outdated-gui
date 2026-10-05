using System.Diagnostics;
using NuGetExtended.Core.Model;
using NuGetExtended.Core.Scanning;

namespace NuGetExtended.Helper.FileApps;

/// Evaluates a file-based app (SDK 10+, a `.cs` file carrying its own `#:package` directives
/// instead of a .csproj) by asking the SDK for its TargetFramework and ProjectAssetsFile.
///
/// `dotnet msbuild <file.cs> -getProperty:...` fails (MSB4025: msbuild does not take .cs files) and
/// `dotnet restore <file.cs> -getProperty:...` runs a real restore (a network side effect this scan
/// must not have). `dotnet build <file.cs> -getProperty:TargetFramework -getProperty:ProjectAssetsFile`
/// only evaluates and prints the same JSON. The CLI's own `-nodeReuse:false` switch is not usable
/// here: passing it as an extra argument makes the CLI treat the whole command line as a plain
/// MSBuild invocation instead of a file-based app (`app.cs` then fails to parse as a project file).
/// MSBUILDDISABLENODEREUSE=1 (the classic MSBuild env var) has the same effect without that problem.
public static class FileBasedApps
{
    public static readonly TimeSpan DefaultTimeout = TimeSpan.FromSeconds(60);

    public static EvaluatedProject Evaluate(string csFile, string dotnetRoot, CancellationToken ct = default, TimeSpan? timeout = null)
    {
        var name = Path.GetFileNameWithoutExtension(csFile);
        var host = Path.Combine(dotnetRoot, OperatingSystem.IsWindows() ? "dotnet.exe" : "dotnet");
        var psi = new ProcessStartInfo(host)
        {
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            WorkingDirectory = Path.GetDirectoryName(csFile)!,
        };
        foreach (var a in new[] { "build", csFile, "-getProperty:TargetFramework", "-getProperty:ProjectAssetsFile" }) psi.ArgumentList.Add(a);
        // SdkHost.Register (MSBuildLocator.RegisterInstance) sets MSBuild*/MSBUILD_EXE_PATH
        // process-wide, for the helper's own MSBuild to find its SDK. Reading psi.Environment
        // inherits the current process environment, so without this a child `dotnet build` would
        // inherit them too and get forced onto the helper's SDK instead of its own (a nested
        // global.json next to this .cs file would then be ignored).
        foreach (var k in psi.Environment.Keys.Where(k => k.StartsWith("MSBuild", StringComparison.OrdinalIgnoreCase)).ToList())
            psi.Environment.Remove(k);
        psi.Environment.Remove("MSBUILD_EXE_PATH");
        psi.Environment["MSBUILDDISABLENODEREUSE"] = "1";

        using var p = Process.Start(psi)!;
        // Both reads start before waiting on exit: a child that fills one pipe's buffer while
        // nothing drains it would otherwise deadlock (it blocks writing, we block reading the other).
        var stdoutTask = p.StandardOutput.ReadToEndAsync();
        var stderrTask = p.StandardError.ReadToEndAsync();

        // WaitForExitAsync(ct) reaches a Canceled state if ct fires before the process exits; the
        // separate, ct-independent delay is what makes a hung child time out even when nothing asks
        // to cancel this scan.
        var exitTask = p.WaitForExitAsync(ct);
        var timeoutTask = Task.Delay(timeout ?? DefaultTimeout, CancellationToken.None);
        var first = Task.WhenAny(exitTask, timeoutTask).GetAwaiter().GetResult();

        if (first == timeoutTask)
        {
            Kill(p);
            return new EvaluatedProject(csFile, name, Array.Empty<EvaluatedTfm>(), new[] { csFile },
                "evaluating the file-based app timed out");
        }
        try
        {
            exitTask.GetAwaiter().GetResult();
        }
        catch (OperationCanceledException)
        {
            Kill(p);
            throw new OperationCanceledException(ct);
        }

        var stdout = stdoutTask.GetAwaiter().GetResult();
        var stderr = stderrTask.GetAwaiter().GetResult();

        if (p.ExitCode != 0)
            return new EvaluatedProject(csFile, name, Array.Empty<EvaluatedTfm>(), new[] { csFile },
                stderr.Trim().Split('\n').FirstOrDefault(l => l.Length > 0) ?? "dotnet build failed evaluating this file-based app");

        if (!GetPropertyJson.TryParse(stdout, out var tfm, out var assets))
            return new EvaluatedProject(csFile, name, Array.Empty<EvaluatedTfm>(), new[] { csFile },
                "could not read the SDK's output for this file-based app");
        var items = Directives.Parse(File.ReadAllText(csFile)).Select(d => new PackageItem(
            d.Id, "PackageReference", d.Version, null,
            d.Version == null ? null : new ValueSite("directive", csFile, d.Line, d.Column, d.Id, "directive", d.Version, null, null, null, null, null),
            null, d.Version == null ? "the directive has no version" : null)).ToList();
        return new EvaluatedProject(csFile, name, new[] { new EvaluatedTfm(tfm, assets, false, false, null, items, Array.Empty<string>()) },
            new[] { csFile }, null);
    }

    private static void Kill(Process p)
    {
        try { p.Kill(entireProcessTree: true); } catch (InvalidOperationException) { }
    }
}
