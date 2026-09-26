using System.Diagnostics;
using Microsoft.Build.Locator;
using NuGetExtended.Core.Protocol;

namespace NuGetExtended.Helper;

public sealed class SdkException : Exception
{
    public SdkException(string message) : base(message) { }
}

/// Registers the SDK that global.json selects for [workDir]. Must run before any MSBuild/NuGet type loads.
public static class SdkHost
{
    public static string SdkPath { get; private set; } = "";
    public static string DotnetRoot { get; private set; } = "";

    public static Hello Register(string workDir)
    {
        VisualStudioInstance? instance;
        try
        {
            instance = MSBuildLocator.QueryVisualStudioInstances(new VisualStudioInstanceQueryOptions
            {
                DiscoveryTypes = DiscoveryType.DotNetSdk,
                WorkingDirectory = workDir,
            }).FirstOrDefault();
        }
        catch (InvalidOperationException e)
        {
            // global.json names an SDK hostfxr cannot resolve: the locator throws (lazily, on
            // enumeration) instead of returning nothing. Its raw message goes to stderr.
            Console.Error.WriteLine(e.Message);
            throw new SdkException("This folder's global.json selects a .NET SDK that is not installed.");
        }
        if (instance == null)
            throw new SdkException("No .NET SDK found. Install the .NET 6 SDK or later.");
        if (instance.Version.Major < 6)
            throw new SdkException($".NET SDK {instance.Version} is too old. Install the .NET 6 SDK or later.");

        MSBuildLocator.RegisterInstance(instance);
        SdkPath = instance.MSBuildPath;
        DotnetRoot = Path.GetFullPath(Path.Combine(instance.MSBuildPath, "..", ".."));

        // Credential providers and restore tasks need the dotnet host that runs us, not an
        // inherited value that may name another dotnet.
        var host = Process.GetCurrentProcess().MainModule?.FileName;
        if (host != null)
            Environment.SetEnvironmentVariable("DOTNET_HOST_PATH", host);

        var helperVersion = typeof(SdkHost).Assembly.GetName().Version?.ToString(3) ?? "0.0.0";
        return new Hello(ProtocolInfo.Version, helperVersion, instance.Version.ToString(), SdkPath, Environment.Version.ToString());
    }
}
