using System.Text.Json;
using System.Text.RegularExpressions;

namespace NuGetExtended.Core.Scanning;

public sealed record Directive(string Id, string? Version, int Line, int Column);

/// Parses the `#:package` directives of a file-based app (SDK 10+, single-file C# apps that carry
/// their own package references instead of a .csproj).
public static class Directives
{
    private static readonly Regex Package = new(@"^#:package\s+([^\s@]+)(?:@(\S+))?\s*$", RegexOptions.Compiled);

    public static IReadOnlyList<Directive> Parse(string text)
    {
        var result = new List<Directive>();
        var lines = text.Split('\n');
        for (var i = 0; i < lines.Length; i++)
        {
            var m = Package.Match(lines[i].TrimEnd('\r'));
            if (m.Success) result.Add(new Directive(m.Groups[1].Value, m.Groups[2].Success ? m.Groups[2].Value : null, i + 1, 1));
        }
        return result;
    }
}

/// Parses the JSON `dotnet build <file.cs> -getProperty:TargetFramework -getProperty:ProjectAssetsFile`
/// prints on stdout. A pure function so it is unit-testable without spawning a process.
public static class GetPropertyJson
{
    public static (string TargetFramework, string ProjectAssetsFile) Parse(string json)
    {
        var props = JsonDocument.Parse(json).RootElement.GetProperty("Properties");
        return (props.GetProperty("TargetFramework").GetString() ?? "", props.GetProperty("ProjectAssetsFile").GetString() ?? "");
    }

    /// False when the output is not that JSON (an extra notice line, a missing property).
    public static bool TryParse(string json, out string targetFramework, out string projectAssetsFile)
    {
        try
        {
            (targetFramework, projectAssetsFile) = Parse(json);
            return true;
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException)
        {
            targetFramework = projectAssetsFile = "";
            return false;
        }
    }
}
