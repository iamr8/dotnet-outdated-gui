using NuGet.Versioning;

namespace NuGetExtended.Core.Versions;

/// Splices an offered version into the raw text of a version attribute, keeping its shape.
/// Rules (spec, Range rule): upper bound kept exactly; lower bound becomes the offered version,
/// inclusive; no lower bound -> [offered,upper]; pin -> new pin; floating -> never edited.
public static class RangeText
{
    public static bool IsFloating(string raw) => raw.Contains('*');

    public static string? Rewrite(string raw, NuGetVersion offered)
    {
        if (IsFloating(raw)) return null;
        var v = offered.ToNormalizedString();

        var start = 0;
        while (start < raw.Length && char.IsWhiteSpace(raw[start])) start++;
        var end = raw.Length;
        while (end > start && char.IsWhiteSpace(raw[end - 1])) end--;
        var lead = raw[..start];
        var trail = raw[end..];
        var core = raw[start..end];

        if (core.Length == 0) return null;
        var open = core[0];
        if (open != '[' && open != '(') return lead + v + trail; // bare minimum version

        var close = core[^1];
        var inner = core[1..^1];
        var comma = inner.IndexOf(',');
        if (comma < 0) return lead + "[" + KeepSpaces(inner, v) + "]" + trail; // pin

        var lower = inner[..comma];
        var upper = inner[comma..]; // keeps ",", its spaces and the upper version
        var newLower = lower.Trim().Length == 0 ? v : KeepSpaces(lower, v);
        return lead + "[" + newLower + upper + close + trail;
    }

    /// Replaces the non-space middle of [part] with [value], keeping the spaces around it.
    private static string KeepSpaces(string part, string value)
    {
        var s = 0;
        while (s < part.Length && char.IsWhiteSpace(part[s])) s++;
        var e = part.Length;
        while (e > s && char.IsWhiteSpace(part[e - 1])) e--;
        return part[..s] + value + part[e..];
    }
}
