using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class ServerTests
{
    private static HelperProcess Start()
    {
        var dir = Path.Combine(Path.GetTempPath(), "hx-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        return HelperProcess.Start(dir);
    }

    [Fact]
    public void PingAnswers()
    {
        using var h = Start();
        var r = h.Request("ping", new { });
        Assert.True(r.GetProperty("result").GetProperty("pong").GetBoolean());
    }

    [Fact]
    public void UnknownMethodIsAnError()
    {
        using var h = Start();
        var r = h.Request("nope", new { });
        Assert.Equal("unknown", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void BadJsonDoesNotKillTheServer()
    {
        using var h = Start();
        h.SendRaw("{not json");
        var r = h.Request("ping", new { });
        Assert.True(r.GetProperty("result").GetProperty("pong").GetBoolean());
    }

    [Fact]
    public void CancelStopsTheActiveRequest()
    {
        using var h = Start();
        var sleepId = h.Send("sleep", new { ms = 30_000 });
        h.Send("cancel", new { targetId = sleepId });
        var r = h.ReadResponse(sleepId, 5_000);
        Assert.Equal("cancelled", r.GetProperty("error").GetProperty("kind").GetString());
    }

    [Fact]
    public void SecondRequestIsBusyWhileOneRuns()
    {
        using var h = Start();
        var sleepId = h.Send("sleep", new { ms = 30_000 });
        var r = h.Request("sleep", new { ms = 1 });
        Assert.Equal("busy", r.GetProperty("error").GetProperty("kind").GetString());
        h.Send("cancel", new { targetId = sleepId });
    }

    [Fact]
    public void ShutdownEndsTheProcess()
    {
        using var h = Start();
        h.Send("shutdown", new { });
        Assert.True(h.WaitForExit(5_000));
    }
}
