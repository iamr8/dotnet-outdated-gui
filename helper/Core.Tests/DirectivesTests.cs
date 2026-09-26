using NuGetExtended.Core.Scanning;
using Xunit;

namespace NuGetExtended.Core.Tests;

public class DirectivesTests
{
    [Fact]
    public void ParsesPackageDirectives()
    {
        var d = Directives.Parse("#!/usr/bin/env dotnet\n#:package Humanizer.Core@2.14.1\n#:package Serilog\n#:property X=1\nConsole.WriteLine();");
        Assert.Equal(2, d.Count);
        Assert.Equal(new Directive("Humanizer.Core", "2.14.1", 2, 1), d[0]);
        Assert.Equal(new Directive("Serilog", null, 3, 1), d[1]);
    }

    [Fact]
    public void FloatingVersionDirectiveKeepsTheStar()
    {
        var d = Directives.Parse("#:package Foo@*");
        Assert.Equal("*", Assert.Single(d).Version);
    }

    [Fact]
    public void CrlfInputParsesTheSameAsLf()
    {
        var lf = Directives.Parse("#:package Humanizer.Core@2.14.1\n#:package Serilog\nConsole.WriteLine();");
        var crlf = Directives.Parse("#:package Humanizer.Core@2.14.1\r\n#:package Serilog\r\nConsole.WriteLine();");
        Assert.Equal(lf, crlf);
    }

    [Fact]
    public void GetPropertyJsonParsesTargetFrameworkAndAssetsFile()
    {
        var json = @"{
  ""Properties"": {
    ""TargetFramework"": ""net10.0"",
    ""ProjectAssetsFile"": ""/Users/x/runfile/app-abc/obj/project.assets.json""
  }
}";
        var (tfm, assets) = GetPropertyJson.Parse(json);
        Assert.Equal("net10.0", tfm);
        Assert.Equal("/Users/x/runfile/app-abc/obj/project.assets.json", assets);
    }

    [Fact]
    public void GetPropertyJsonTryParseRejectsOutputThatIsNotTheJson()
    {
        // A first-run or workload notice on stdout must not become a bug report.
        Assert.False(GetPropertyJson.TryParse("Welcome to .NET!\n{}", out _, out _));
        Assert.False(GetPropertyJson.TryParse(@"{ ""Properties"": { } }", out _, out _));
        Assert.True(GetPropertyJson.TryParse(@"{ ""Properties"": { ""TargetFramework"": ""net10.0"", ""ProjectAssetsFile"": ""/a.json"" } }", out var tfm, out var assets));
        Assert.Equal("net10.0", tfm);
        Assert.Equal("/a.json", assets);
    }
}
