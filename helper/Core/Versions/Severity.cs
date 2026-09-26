using NuGet.Versioning;

namespace NuGetExtended.Core.Versions;

public static class Severity
{
    public static string Of(NuGetVersion resolved, NuGetVersion? target)
    {
        if (target == null || target <= resolved) return "None";
        if (target.IsPrerelease) return "Major";
        if (target.Major != resolved.Major) return "Major";
        if (target.Minor != resolved.Minor) return "Minor";
        return "Patch";
    }
}
