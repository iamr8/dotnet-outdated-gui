using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class HandshakeTests
{
    [Fact]
    public void FirstLineIsHelloWithSdkAndProtocol()
    {
        var dir = FixtureSolution.NewDir();
        using var helper = HelperProcess.Start(dir);

        Assert.Equal("hello", helper.HelloLine.GetProperty("event").GetString());
        var data = helper.HelloLine.GetProperty("data");
        Assert.Equal(1, data.GetProperty("protocol").GetInt32());
        var sdkMajor = Version.Parse(data.GetProperty("sdkVersion").GetString()!.Split('-')[0]).Major;
        Assert.True(sdkMajor >= 6);
        // When CI pins the SDK under test (HELPER_TEST_SDK), the helper must actually load it, not
        // whatever SDK happens to be newest on the runner.
        if (FixtureSolution.PinnedSdkMajor is { } pinned) Assert.Equal(pinned, sdkMajor);
        Assert.False(string.IsNullOrEmpty(data.GetProperty("sdkPath").GetString()));
    }

    [Fact]
    public void GlobalJsonWithAMissingSdkIsAFatalEventNotACrash()
    {
        var dir = FixtureSolution.NewDir();
        FixtureSolution.Write(dir, "global.json", @"{""sdk"":{""version"":""1.0.100"",""rollForward"":""disable""}}");
        using var helper = HelperProcess.Start(dir);

        Assert.Equal("fatal", helper.HelloLine.GetProperty("event").GetString());
        Assert.Equal("This folder's global.json selects a .NET SDK that is not installed.",
            helper.HelloLine.GetProperty("data").GetProperty("message").GetString());
        Assert.True(helper.WaitForExit(10_000));
        Assert.Equal(2, helper.ExitCode);
    }

    [Fact]
    public void OutputFolderShipsNoNuGetOrMSBuildAssemblies()
    {
        var outDir = Path.GetDirectoryName(HelperProcess.HelperDll)!;
        var shipped = Directory.GetFiles(outDir, "*.dll").Select(Path.GetFileName).ToList();
        Assert.DoesNotContain(shipped, f => f!.StartsWith("NuGet.", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain(shipped, f => f!.StartsWith("Microsoft.Build.", StringComparison.OrdinalIgnoreCase) &&
                                            !f.Equals("Microsoft.Build.Locator.dll", StringComparison.OrdinalIgnoreCase));
        Assert.DoesNotContain("Microsoft.Build.dll", shipped);
    }
}
