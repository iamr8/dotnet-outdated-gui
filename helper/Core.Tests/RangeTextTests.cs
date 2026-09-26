using NuGet.Versioning;
using NuGetExtended.Core.Versions;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class RangeTextTests
{
    [Theory]
    [InlineData("1.2.3", "1.4.0", "1.4.0")]
    [InlineData("[12.0.1,14.0.0)", "13.0.4", "[13.0.4,14.0.0)")]
    [InlineData("[7.0.0,8.0.0)", "7.2.4", "[7.2.4,8.0.0)")]
    [InlineData("(1.0.0,2.0.0)", "1.5.0", "[1.5.0,2.0.0)")]
    [InlineData("(,3.0.0]", "3.0.0", "[3.0.0,3.0.0]")]
    [InlineData("(,3.0.0)", "2.12.0", "[2.12.0,3.0.0)")]
    [InlineData("[1.2.3]", "1.4.0", "[1.4.0]")]
    [InlineData("[1.0, 2.0)", "1.5.0", "[1.5.0, 2.0)")]
    [InlineData(" 1.2.3 ", "1.4.0", " 1.4.0 ")]
    [InlineData("[ 1.2.3 ]", "1.4.0", "[ 1.4.0 ]")]
    [InlineData("[1.0.0,)", "2.0.0", "[2.0.0,)")]
    public void RewritesKeepingShape(string raw, string offered, string expected)
    {
        Assert.Equal(expected, RangeText.Rewrite(raw, NuGetVersion.Parse(offered)));
    }

    [Theory]
    [InlineData("2.*")]
    [InlineData("2.1.*")]
    [InlineData("*")]
    [InlineData("1.0.0-*")]
    [InlineData("[1.0.*,2.0.0)")]
    public void FloatingIsNeverRewritten(string raw)
    {
        Assert.True(RangeText.IsFloating(raw));
        Assert.Null(RangeText.Rewrite(raw, NuGetVersion.Parse("9.9.9")));
    }
}
