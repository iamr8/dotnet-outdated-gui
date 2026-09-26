using System.Runtime.CompilerServices;
using NuGetExtended.Core.Protocol;

namespace NuGetExtended.Helper;

public static class App
{
    /// NoInlining: keeps MSBuild/NuGet types out of Main, which runs before registration.
    [MethodImpl(MethodImplOptions.NoInlining)]
    public static Task<int> RunAsync(Hello hello, TextReader input, TextWriter output)
    {
        var server = new Server(input, output);
        // The debug methods below (sleep, versions, evaluate, assets, memory) are for tests and
        // diagnostics. They are reachable on stdin only, so they are not gated.
        // Debug-only method: lets tests exercise cancel/busy without a real scan.
        server.Register("sleep", async (@params, _, ct) =>
        {
            var ms = @params.GetProperty("ms").GetInt32();
            await Task.Delay(ms, ct);
            return new { slept = ms };
        });
        // Debug-only method: lets tests exercise FeedService without a real scan.
        // A new FeedService per call, not one for the process: the helper lives long, and a
        // process-lifetime instance would keep stale cached failures and settings forever (Task A8
        // builds "scan" the same way).
        server.Register("versions", async (p, _, ct) =>
        {
            using var feeds = new Feeds.FeedService(new Core.Versions.ScanOptions());
            var ctx = feeds.Context(p.GetProperty("projectDir").GetString()!, Array.Empty<string>());
            var list = await feeds.GetVersionsAsync(ctx, p.GetProperty("id").GetString()!, ct);
            return new
            {
                versions = list.Versions.Select(v => v.ToNormalizedString()),
                failures = feeds.Failures,
            };
        });
        var evaluator = new Projects.ProjectEvaluator(SdkHost.DotnetRoot, hello.SdkVersion);
        // Debug-only method: lets tests exercise ProjectEvaluator without a real scan.
        server.Register("evaluate", (p, _, _) => Task.FromResult<object>(
            evaluator.Evaluate(p.GetProperty("path").GetString()!, p.GetProperty("runtime").GetString() ?? "")));
        // Debug-only method: lets tests exercise AssetsReader/RestoreState without a real scan.
        server.Register("assets", (p, _, _) =>
        {
            var project = evaluator.Evaluate(p.GetProperty("path").GetString()!, "");
            var tfm = project.Frameworks[0];
            var data = Projects.AssetsReader.Read(tfm.AssetsFile, tfm.Framework, "");
            return Task.FromResult<object>(new
            {
                stale = Projects.RestoreState.IsStale(project, ""),
                packages = data?.Packages.Select(a => new { id = a.Id, requestedRange = a.RequestedRange, resolved = a.Resolved?.ToNormalizedString(), direct = a.Direct, depth = a.Depth }) ?? Enumerable.Empty<object>(),
            });
        });
        var handlers = new Handlers(evaluator);
        server.Register("scan", handlers.Scan);
        server.Register("planUpgrade", handlers.PlanUpgrade);
        server.Register("invalidate", handlers.Invalidate);
        server.Register("memory", (_, _, _) =>
        {
            GC.Collect(); GC.WaitForPendingFinalizers(); GC.Collect();
            return Task.FromResult<object>(new { bytes = GC.GetTotalMemory(true) });
        });
        return server.RunAsync();
    }
}
