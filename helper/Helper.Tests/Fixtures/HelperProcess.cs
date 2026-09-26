using System.Diagnostics;
using System.Reflection;
using System.Text.Json;
using NuGetExtended.Core.Protocol;

namespace NuGetExtended.Helper.Tests.Fixtures;

/// Starts the real helper process and talks the JSON-lines protocol with it.
public sealed class HelperProcess : IDisposable
{
    private readonly Process _process;
    private int _nextId;

    public JsonElement HelloLine { get; }

    private HelperProcess(Process process, JsonElement hello)
    {
        _process = process;
        HelloLine = hello;
    }

    public static string HelperDll => typeof(HelperProcess).Assembly
        .GetCustomAttributes<AssemblyMetadataAttribute>()
        .Single(a => a.Key == "HelperDll").Value!;

    public static HelperProcess Start(string workDir, IDictionary<string, string>? env = null)
    {
        var psi = new ProcessStartInfo("dotnet")
        {
            WorkingDirectory = workDir,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
        };
        psi.ArgumentList.Add(HelperDll);
        psi.ArgumentList.Add(workDir);
        FixtureSolution.ScrubMsBuildEnv(psi);
        foreach (var (k, v) in env ?? new Dictionary<string, string>()) psi.Environment[k] = v;
        var p = Process.Start(psi)!;
        p.ErrorDataReceived += (_, _) => { };
        p.BeginErrorReadLine();
        // hostfxr (native) prints the installed SDK list to stdout when a global.json pin fails;
        // the plugin's client drops lines that are not JSON, and so does this.
        string? first;
        do
        {
            var lineTask = p.StandardOutput.ReadLineAsync();
            if (!lineTask.Wait(60_000))
            {
                try { p.Kill(true); } catch (InvalidOperationException) { }
                p.Dispose();
                throw new InvalidOperationException("helper wrote nothing within 60s");
            }
            first = lineTask.Result ?? throw new InvalidOperationException("helper wrote nothing");
        } while (!first.TrimStart().StartsWith("{"));
        return new HelperProcess(p, JsonDocument.Parse(first).RootElement.Clone());
    }

    /// Writes one request line and returns its id, without waiting for a response.
    public int Send(string method, object @params)
    {
        var id = Interlocked.Increment(ref _nextId);
        var line = JsonSerializer.Serialize(new { id, method, @params }, Json.Options);
        _process.StandardInput.WriteLine(line);
        _process.StandardInput.Flush();
        return id;
    }

    /// Reads lines until the one whose id matches; events and other ids are skipped.
    /// Each read is bounded, so a helper that never answers fails at the timeout instead of hanging.
    /// [events] collects the progress events of request [eventsId] (default: [id]) seen on the way.
    public JsonElement ReadResponse(int id, int timeoutMs = 120_000, List<JsonElement>? events = null, int? eventsId = null)
    {
        var sw = Stopwatch.StartNew();
        while (true)
        {
            var remaining = timeoutMs - sw.ElapsedMilliseconds;
            if (remaining <= 0) throw new TimeoutException($"id {id}");
            var readTask = _process.StandardOutput.ReadLineAsync();
            if (!readTask.Wait(TimeSpan.FromMilliseconds(remaining))) throw new TimeoutException($"id {id}");
            var read = readTask.Result ?? throw new InvalidOperationException("helper closed stdout");
            var doc = JsonDocument.Parse(read).RootElement.Clone();
            if (events != null && doc.TryGetProperty("event", out _) && doc.TryGetProperty("id", out var eid) &&
                eid.ValueKind == JsonValueKind.Number && eid.GetInt32() == (eventsId ?? id))
                events.Add(doc);
            if (doc.TryGetProperty("id", out var rid) && rid.ValueKind == JsonValueKind.Number &&
                rid.GetInt32() == id && !doc.TryGetProperty("event", out _))
                return doc;
        }
    }

    /// Sends one request and returns the response line (events and other ids before it are skipped).
    public JsonElement Request(string method, object @params, int timeoutMs = 120_000)
    {
        var id = Send(method, @params);
        return ReadResponse(id, timeoutMs);
    }

    public void SendRaw(string line)
    {
        _process.StandardInput.WriteLine(line);
        _process.StandardInput.Flush();
    }

    public bool WaitForExit(int ms) => _process.WaitForExit(ms);

    public int ExitCode => _process.ExitCode;

    public void Dispose()
    {
        try
        {
            // The process may already have exited (a test can shut it down itself); a write
            // to its closed stdin pipe then throws IOException ("Broken pipe") rather than
            // InvalidOperationException, so both are tolerated here.
            if (!_process.HasExited)
            {
                _process.StandardInput.WriteLine("{\"id\":0,\"method\":\"shutdown\",\"params\":{}}");
                _process.StandardInput.Flush();
            }
            if (!_process.WaitForExit(5000)) _process.Kill(true);
        }
        catch (InvalidOperationException) { }
        catch (IOException) { }
        _process.Dispose();
    }
}
