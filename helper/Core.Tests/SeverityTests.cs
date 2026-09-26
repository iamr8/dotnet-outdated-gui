using NuGet.Versioning;
using NuGetExtended.Core.Versions;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class SeverityTests
{
    [Theory]
    [InlineData("1.0.0", null, "None")]
    [InlineData("1.0.0", "1.0.0", "None")]
    [InlineData("1.0.0", "1.0.1", "Patch")]
    [InlineData("1.0.0.0", "1.0.0.1", "Patch")]
    [InlineData("1.0.0", "1.1.0", "Minor")]
    [InlineData("1.0.0", "2.0.0", "Major")]
    [InlineData("1.0.0", "1.0.1-beta", "Major")]
    public void Classifies(string resolved, string? target, string expected)
    {
        Assert.Equal(expected, Severity.Of(NuGetVersion.Parse(resolved), target == null ? null : NuGetVersion.Parse(target)));
    }
}
