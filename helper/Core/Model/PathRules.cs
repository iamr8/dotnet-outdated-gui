namespace NuGetExtended.Core.Model;

public static class PathRules
{
    /// True when [file] is inside the [root] folder. Case is ignored on Windows only; symlinks are
    /// not followed.
    public static bool IsUnder(string file, string root) =>
        Path.GetFullPath(file).StartsWith(Path.GetFullPath(root).TrimEnd('/', '\\') + Path.DirectorySeparatorChar,
            OperatingSystem.IsWindows() ? StringComparison.OrdinalIgnoreCase : StringComparison.Ordinal);
}
