using NuGet.Frameworks;
using NuGet.Versioning;
using NuGetExtended.Core.Versions;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class TargetSelectorTests
{
    private static readonly DateTimeOffset Now = new(2026, 9, 25, 0, 0, 0, TimeSpan.Zero);
    private static readonly NuGetFramework Net8 = NuGetFramework.Parse("net8.0");

    private static List<Candidate> C(params string[] versions) =>
        versions.Select(v => new Candidate(NuGetVersion.Parse(v), true, Now.AddDays(-400), Array.Empty<NuGetFramework>())).ToList();

    private static Selection Pick(string requested, string resolved, List<Candidate> c, ScanOptions? o = null) =>
        TargetSelector.Select(VersionRange.Parse(requested), NuGetVersion.Parse(resolved), c, Net8, false, o ?? new ScanOptions(), Now);

    [Fact]
    public void MinimumVersionTakesLatestStable()
    {
        var s = Pick("1.2.3", "1.2.3", C("1.2.3", "1.4.0", "2.0.0", "2.1.0-beta"));
        Assert.Equal("2.0.0", s.Target?.ToNormalizedString());
        Assert.Null(s.Capped);
    }

    [Fact]
    public void RangeCapsAndReportsNewerOutside()
    {
        var s = Pick("[7.0.0,8.0.0)", "7.0.0", C("7.0.0", "7.2.4", "8.8.0"));
        Assert.Equal("7.2.4", s.Target?.ToNormalizedString());
        Assert.Equal("8.8.0", s.Capped?.ToNormalizedString());
    }

    [Fact]
    public void UpperOnlyRangeDoesNotCrashAndStaysInside()
    {
        var s = Pick("(,3.0.0]", "0.1.6", C("0.1.6", "2.12.0", "3.0.0", "4.4.0"));
        Assert.Equal("3.0.0", s.Target?.ToNormalizedString());
        Assert.Equal("4.4.0", s.Capped?.ToNormalizedString());
    }

    [Fact]
    public void PinMoves()
    {
        var s = Pick("[1.2.3]", "1.2.3", C("1.2.3", "1.4.0"));
        Assert.Equal("1.4.0", s.Target?.ToNormalizedString());
    }

    [Fact]
    public void VersionLockMajorStaysInMajor()
    {
        var s = Pick("1.2.3", "1.2.3", C("1.2.3", "1.9.0", "2.0.0"), new ScanOptions(VersionLock: "Major"));
        Assert.Equal("1.9.0", s.Target?.ToNormalizedString());
    }

    [Fact]
    public void VersionLockMinorStaysInMinor()
    {
        var s = Pick("1.2.3", "1.2.3", C("1.2.3", "1.2.9", "1.3.0"), new ScanOptions(VersionLock: "Minor"));
        Assert.Equal("1.2.9", s.Target?.ToNormalizedString());
    }

    [Fact]
    public void PrereleaseAutoFollowsResolved()
    {
        var c = C("1.0.0-beta.2", "1.0.0-beta.10", "1.0.0");
        Assert.Equal("1.0.0", Pick("1.0.0-beta.2", "1.0.0-beta.2", c).Target?.ToNormalizedString());
        Assert.Null(Pick("1.0.0", "1.0.0", c).Target);
        Assert.Equal("1.0.0", Pick("1.0.0-beta.2", "1.0.0-beta.2", c, new ScanOptions(PreRelease: "Never")).Target?.ToNormalizedString());
    }

    [Fact]
    public void NumericPrereleasePartsCompareAsNumbers()
    {
        var s = Pick("1.0.0-beta.2", "1.0.0-beta.2", C("1.0.0-beta.2", "1.0.0-beta.10"), new ScanOptions(PreRelease: "Always"));
        Assert.Equal("1.0.0-beta.10", s.Target?.ToNormalizedString());
    }

    [Fact]
    public void MaximumVersionCapsNeverWidens()
    {
        Assert.Equal("1.9.0", Pick("1.0.0", "1.0.0", C("1.0.0", "1.9.0", "2.5.0"), new ScanOptions(MaximumVersion: "1.9")).Target?.ToNormalizedString());
        Assert.Equal("7.2.4", Pick("[7.0.0,8.0.0)", "7.0.0", C("7.0.0", "7.2.4", "8.8.0"), new ScanOptions(MaximumVersion: "9.0")).Target?.ToNormalizedString());
    }

    [Fact]
    public void OlderThanDaysSkipsFreshVersions()
    {
        var c = new List<Candidate>
        {
            new(NuGetVersion.Parse("1.0.0"), true, Now.AddDays(-100), Array.Empty<NuGetFramework>()),
            new(NuGetVersion.Parse("1.1.0"), true, Now.AddDays(-2), Array.Empty<NuGetFramework>()),
        };
        Assert.Null(Pick("1.0.0", "1.0.0", c, new ScanOptions(OlderThanDays: 7)).Target);
    }

    [Fact]
    public void UnlistedAndIncompatibleAreSkipped()
    {
        var c = new List<Candidate>
        {
            new(NuGetVersion.Parse("1.0.0"), true, null, Array.Empty<NuGetFramework>()),
            new(NuGetVersion.Parse("1.1.0"), false, null, Array.Empty<NuGetFramework>()),
            new(NuGetVersion.Parse("1.2.0"), true, null, new[] { NuGetFramework.Parse("net9.0") }),
        };
        Assert.Null(Pick("1.0.0", "1.0.0", c).Target);
    }

    [Fact]
    public void FloatingWithNewerMatchIsRestoreOnly()
    {
        var s = Pick("2.*", "2.10.0", C("2.10.0", "2.14.1", "3.0.10"));
        Assert.True(s.RestoreOnly);
        Assert.Equal("2.14.1", s.Target?.ToNormalizedString());
        Assert.Equal("3.0.10", s.Capped?.ToNormalizedString());
    }

    [Fact]
    public void FloatingAlreadyAtBestIsCappedOnly()
    {
        var s = Pick("2.*", "2.14.1", C("2.14.1", "3.0.10"));
        Assert.False(s.RestoreOnly);
        Assert.Null(s.Target);
        Assert.Equal("3.0.10", s.Capped?.ToNormalizedString());
    }

    [Fact]
    public void FloatingRestoreOnlyRespectsVersionLock()
    {
        var s = Pick("*", "1.0.0", C("1.0.0", "2.0.0"), new ScanOptions(VersionLock: "Major"));
        Assert.False(s.RestoreOnly); // restore would pick 2.0.0, which the lock forbids
        Assert.Equal("2.0.0", s.Capped?.ToNormalizedString());
    }

    [Fact]
    public void VersionLockMajorDoesNotEscapeToNewerMajorWhenResolvedIsUnlisted()
    {
        var s = Pick("1.2.3", "1.2.3", C("2.0.0"), new ScanOptions(VersionLock: "Major"));
        Assert.Null(s.Target);
    }

    [Fact]
    public void VersionLockMinorDoesNotEscapeToNewerMinorWhenResolvedIsUnlisted()
    {
        var s = Pick("1.2.3", "1.2.3", C("1.3.0"), new ScanOptions(VersionLock: "Minor"));
        Assert.Null(s.Target);
    }

    [Fact]
    public void PreReleaseNeverWithVersionLockMajorStaysWithinMajorOfResolved()
    {
        var c = C("1.0.0-beta.2", "1.0.0", "2.0.0");
        var s = Pick("1.0.0-beta.2", "1.0.0-beta.2", c, new ScanOptions(VersionLock: "Major", PreRelease: "Never"));
        Assert.Equal("1.0.0", s.Target?.ToNormalizedString());
    }

    [Fact]
    public void FloatingOlderThanDaysBlocksRestoreOnlyButReportsCapped()
    {
        var c = new List<Candidate>
        {
            new(NuGetVersion.Parse("2.10.0"), true, Now.AddDays(-400), Array.Empty<NuGetFramework>()),
            new(NuGetVersion.Parse("2.14.1"), true, Now.AddDays(-400), Array.Empty<NuGetFramework>()),
            new(NuGetVersion.Parse("2.15.0"), true, Now.AddDays(-2), Array.Empty<NuGetFramework>()),
        };
        var s = Pick("2.*", "2.10.0", c, new ScanOptions(OlderThanDays: 7));
        Assert.False(s.RestoreOnly);
        Assert.Null(s.Target);
        Assert.Equal("2.15.0", s.Capped?.ToNormalizedString());
    }

    [Fact]
    public void FloatingAllRestoreOnlyBlockedByLockReportsHighestAsCapped()
    {
        var s = Pick("*", "1.0.0", C("1.0.0", "1.5.0", "2.0.0"), new ScanOptions(VersionLock: "Major"));
        Assert.False(s.RestoreOnly);
        Assert.Equal("2.0.0", s.Capped?.ToNormalizedString());
    }

    [Fact]
    public void FloatingMaximumVersionBlocksRestoreOnlyButReportsCapped()
    {
        var s = Pick("2.*", "2.10.0", C("2.10.0", "2.14.1", "2.15.0"), new ScanOptions(MaximumVersion: "2.14"));
        Assert.False(s.RestoreOnly);
        Assert.Equal("2.15.0", s.Capped?.ToNormalizedString());
    }
}
