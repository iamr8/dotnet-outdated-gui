using System.Net;
using System.Text;
using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

/// Package sources are queried in parallel and merged in source order.
/// Each test holds an earlier source until a later one has answered. A one-by-one loop cannot pass.
public class FeedConcurrencyTests
{
    // Break: query the sources one after another (the first source waits for the second and never gets it).
    [Fact]
    public void SourcesAreQueriedInParallel()
    {
        using var first = new FakeFeed("Pkg", "1.0.0");
        using var second = new FakeFeed("Pkg", "2.0.0");
        first.HoldFlat = second.FlatAnswered;
        var dir = FixtureSolution.NewDir();
        FixtureSolution.WriteNuGetConfig(dir, ("first", first.IndexUrl), ("second", second.IndexUrl));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("versions", new { projectDir = dir, id = "Pkg" }).GetProperty("result");

        Assert.Equal(new[] { "1.0.0", "2.0.0" }, r.GetProperty("versions").EnumerateArray().Select(v => v.GetString()));
        Assert.Empty(r.GetProperty("failures").EnumerateArray());
    }

    // Break: merge candidates in the order the sources answer, not in source order (the fast source wins).
    [Fact]
    public void CandidatesMergeInSourceOrderWhenALaterSourceAnswersFirst()
    {
        var (dir, project, local) = Setup();
        // Same version, different metadata: the slow source says 2.0.0 needs a framework the project lacks.
        using var slow = new FakeFeed("Pkg", "2.0.0", dependencyFramework: "net99.0");
        using var fast = new FakeFeed("Pkg", "2.0.0");
        slow.HoldRegistration = fast.RegistrationAnswered;
        FixtureSolution.WriteNuGetConfig(dir, ("local", local), ("slow", slow.IndexUrl), ("fast", fast.IndexUrl));
        using var h = HelperProcess.Start(dir);

        var first = Target(h, dir, project);

        Assert.Null(first); // the slow source is first in the config, so its metadata wins

        // Control: the same feeds in the other order. The fast source now wins, so 2.0.0 is offered.
        FixtureSolution.WriteNuGetConfig(dir, ("local", local), ("fast", fast.IndexUrl), ("slow", slow.IndexUrl));
        Assert.Equal("2.0.0", Target(h, dir, project));
    }

    // Break: surface the first source that fails in time, not the first one in source order.
    [Fact]
    public void FirstFailingSourceInOrderIsTheUserError()
    {
        var (dir, project, local) = Setup();
        using var first = new FakeFeed("Pkg", "2.0.0", failFlat: true);
        using var second = new FakeFeed("Pkg", "2.0.0", failFlat: true);
        first.HoldFlat = second.FlatAnswered; // the second source fails first, the first one right after
        FixtureSolution.WriteNuGetConfig(dir, ("local", local), ("first", first.IndexUrl), ("second", second.IndexUrl));
        using var h = HelperProcess.Start(dir);

        var r = h.Request("scan", new { solutionDir = dir, projects = new[] { project }, options = new { ignoreFailedSources = false } });

        var error = r.GetProperty("error");
        Assert.Equal("user", error.GetProperty("kind").GetString());
        Assert.StartsWith("Package source 'first' failed", error.GetProperty("message").GetString());
    }

    private static (string dir, string project, string local) Setup()
    {
        var dir = FixtureSolution.NewDir();
        var local = FixtureFeed.Create(dir, ("Pkg", new[] { "1.0.0" }));
        FixtureSolution.WriteNuGetConfig(dir, ("local", local));
        FixtureSolution.Write(dir, "P/P.csproj", $@"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>{FixtureSolution.Tfm}</TargetFramework></PropertyGroup>
  <ItemGroup><PackageReference Include=""Pkg"" Version=""1.0.0"" /></ItemGroup>
</Project>");
        var project = Path.Combine(dir, "P/P.csproj");
        FixtureSolution.Restore(project); // against the local feed only: the fake sources come after
        return (dir, project, local);
    }

    private static string? Target(HelperProcess h, string dir, string project)
    {
        var response = h.Request("scan", new { solutionDir = dir, projects = new[] { project }, options = new { includeUpToDate = true } });
        Assert.True(response.TryGetProperty("result", out var r) && r.ValueKind == System.Text.Json.JsonValueKind.Object, response.ToString());
        var row = r.GetProperty("projects")[0].GetProperty("frameworks")[0].GetProperty("packages").EnumerateArray()
            .Single(p => p.GetProperty("id").GetString() == "Pkg");
        return row.GetProperty("target").GetString();
    }

    /// A V3 source for one package with one version. A request kind can be held until a task completes,
    /// so this source answers after another one. [FlatAnswered] and [RegistrationAnswered] tell when it answered.
    private sealed class FakeFeed : IDisposable
    {
        // Safety bound only: a request that is never released answers 500 after this, so a broken
        // implementation fails the test instead of hanging it.
        private static readonly TimeSpan HoldLimit = TimeSpan.FromSeconds(30);

        private readonly HttpListener _listener = new();
        private readonly string _baseUrl;
        private readonly string _id;
        private readonly string _version;
        private readonly string? _dependencyFramework;
        private readonly bool _failFlat;
        private readonly TaskCompletionSource _flatAnswered = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private readonly TaskCompletionSource _registrationAnswered = new(TaskCreationOptions.RunContinuationsAsynchronously);
        private volatile bool _gaveUp;

        public Task? HoldFlat { get; set; }
        public Task? HoldRegistration { get; set; }
        public Task FlatAnswered => _flatAnswered.Task;
        public Task RegistrationAnswered => _registrationAnswered.Task;
        public string IndexUrl => _baseUrl + "v3/index.json";

        public FakeFeed(string id, string version, string? dependencyFramework = null, bool failFlat = false)
        {
            _id = id;
            _version = version;
            _dependencyFramework = dependencyFramework;
            _failFlat = failFlat;
            var l = new System.Net.Sockets.TcpListener(IPAddress.Loopback, 0);
            l.Start();
            var port = ((IPEndPoint)l.LocalEndpoint).Port;
            l.Stop();
            _baseUrl = $"http://127.0.0.1:{port}/";
            _listener.Prefixes.Add(_baseUrl);
            _listener.Start();
            _ = Task.Run(async () =>
            {
                while (_listener.IsListening)
                {
                    HttpListenerContext ctx;
                    try { ctx = await _listener.GetContextAsync(); } catch { break; }
                    _ = Task.Run(() => Handle(ctx));
                }
            });
        }

        private async Task Handle(HttpListenerContext ctx)
        {
            try
            {
                var path = ctx.Request.Url!.AbsolutePath;
                if (path.EndsWith("/v3/index.json"))
                {
                    // 3.4.0: the oldest NuGet client in the supported range (SDK 6) does not know 3.6.0.
                    Json(ctx, 200, "{\"version\":\"3.0.0\",\"resources\":[" +
                        "{\"@id\":\"" + _baseUrl + "flat/\",\"@type\":\"PackageBaseAddress/3.0.0\"}," +
                        "{\"@id\":\"" + _baseUrl + "reg/\",\"@type\":\"RegistrationsBaseUrl/3.4.0\"}]}");
                    return;
                }
                var isFlat = path.Contains("/flat/");
                var hold = isFlat ? HoldFlat : HoldRegistration;
                if (hold != null && !hold.IsCompleted && !_gaveUp) await Task.WhenAny(hold, Task.Delay(HoldLimit));
                if (hold != null && !hold.IsCompleted)
                {
                    _gaveUp = true;
                    Json(ctx, 500, "{}");
                }
                else if (isFlat && _failFlat) Json(ctx, 401, "{}");
                else Json(ctx, 200, isFlat ? Flat() : Registration());
                (isFlat ? _flatAnswered : _registrationAnswered).TrySetResult();
            }
            catch (Exception e) when (e is HttpListenerException or ObjectDisposedException or InvalidOperationException)
            {
                // The helper dropped the connection (cancel, shutdown): nothing to answer.
            }
        }

        private string Flat() => "{\"versions\":[\"" + _version + "\"]}";

        private string Registration()
        {
            var groups = _dependencyFramework == null ? "" : ",\"dependencyGroups\":[{\"targetFramework\":\"" + _dependencyFramework + "\"}]";
            var lower = _id.ToLowerInvariant();
            return "{\"@id\":\"" + _baseUrl + "reg/" + lower + "/index.json\",\"count\":1,\"items\":[{" +
                "\"@id\":\"" + _baseUrl + "reg/" + lower + "/page.json\",\"count\":1,\"lower\":\"" + _version + "\",\"upper\":\"" + _version + "\",\"items\":[{" +
                "\"@id\":\"" + _baseUrl + "reg/" + lower + "/" + _version + ".json\"," +
                "\"catalogEntry\":{\"@id\":\"" + _baseUrl + "cat/" + _version + ".json\",\"id\":\"" + _id + "\",\"version\":\"" + _version + "\"," +
                "\"listed\":true,\"published\":\"2020-01-01T00:00:00Z\"" + groups + "}," +
                "\"packageContent\":\"" + _baseUrl + "flat/" + lower + "/" + _version + "/" + lower + "." + _version + ".nupkg\"}]}]}";
        }

        private static void Json(HttpListenerContext ctx, int status, string json)
        {
            var bytes = Encoding.UTF8.GetBytes(json);
            ctx.Response.StatusCode = status;
            ctx.Response.ContentType = "application/json";
            ctx.Response.ContentLength64 = bytes.Length;
            ctx.Response.OutputStream.Write(bytes, 0, bytes.Length);
            ctx.Response.Close();
        }

        public void Dispose() => _listener.Close();
    }
}
