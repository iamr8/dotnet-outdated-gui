using System.Text.Json;
using NuGetExtended.Helper.Tests.Fixtures;
using Xunit;

namespace NuGetExtended.Helper.Tests;

public class ProjectEvaluatorTests
{
    private static JsonElement Evaluate(string dir, string project)
    {
        using var h = HelperProcess.Start(dir);
        return h.Request("evaluate", new { path = Path.Combine(dir, project), runtime = "" }).GetProperty("result");
    }

    private static JsonElement Item(JsonElement tfm, string type, string id) =>
        tfm.GetProperty("items").EnumerateArray().Single(i =>
            i.GetProperty("itemType").GetString() == type && i.GetProperty("id").GetString() == id);

    [Fact]
    public void UpdateInImportedFileOwnsTheVersion()
    {
        var dir = FixtureSolution.NewDir();
        FixtureSolution.Write(dir, "Directory.Build.targets", @"<Project>
  <PropertyGroup><PollyVer>7.2.4</PollyVer></PropertyGroup>
  <ItemGroup><PackageReference Update=""Serilog"" Version=""3.0.0"" /></ItemGroup>
</Project>");
        FixtureSolution.Write(dir, "U/U.csproj", @"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFrameworks>net6.0;net8.0</TargetFrameworks></PropertyGroup>
  <ItemGroup>
    <PackageReference Include=""Serilog"" Version=""2.0.0"" />
    <PackageReference Include=""Polly""><Version>$(PollyVer)</Version></PackageReference>
  </ItemGroup>
  <ItemGroup Condition=""'$(TargetFramework)' == 'net8.0'"">
    <PackageReference Include=""Dapper"" Version=""2.0.4"" />
  </ItemGroup>
</Project>");

        var p = Evaluate(dir, "U/U.csproj");
        var tfms = p.GetProperty("frameworks").EnumerateArray().ToList();
        Assert.Equal(new[] { "net6.0", "net8.0" }, tfms.Select(t => t.GetProperty("framework").GetString()));

        var serilog = Item(tfms[0], "PackageReference", "Serilog").GetProperty("versionSite");
        Assert.Equal("Update", serilog.GetProperty("identityAttr").GetString());
        Assert.EndsWith("Directory.Build.targets", serilog.GetProperty("file").GetString());
        Assert.Equal("3.0.0", serilog.GetProperty("rawText").GetString());

        var polly = Item(tfms[0], "PackageReference", "Polly").GetProperty("versionSite");
        Assert.Equal("property", polly.GetProperty("kind").GetString());
        Assert.Equal("PollyVer", polly.GetProperty("name").GetString());
        Assert.Equal(2, polly.GetProperty("line").GetInt32());

        Assert.DoesNotContain(tfms[0].GetProperty("items").EnumerateArray(), i => i.GetProperty("id").GetString() == "Dapper");
        var dapper = Item(tfms[1], "PackageReference", "Dapper").GetProperty("versionSite");
        Assert.Equal("'$(TargetFramework)' == 'net8.0'", dapper.GetProperty("groupCondition").GetString());

        var inputs = p.GetProperty("inputs").EnumerateArray().Select(i => Path.GetFileName(i.GetString())).ToList();
        Assert.Contains("U.csproj", inputs);
        Assert.Contains("Directory.Build.targets", inputs);
        Assert.DoesNotContain(inputs, f => f!.EndsWith("WorkloadManifest.targets"));
    }

    [Fact]
    public void CentralPackageManagementAndOverride()
    {
        var dir = FixtureSolution.NewDir();
        FixtureSolution.Write(dir, "Directory.Packages.props", @"<Project>
  <PropertyGroup><ManagePackageVersionsCentrally>true</ManagePackageVersionsCentrally></PropertyGroup>
  <ItemGroup>
    <PackageVersion Include=""Newtonsoft.Json"" Version=""[12.0.1,14.0.0)"" />
    <PackageVersion Include=""Polly"" Version=""7.0.0"" />
    <GlobalPackageReference Include=""Nerdbank.GitVersioning"" Version=""3.6.0"" />
  </ItemGroup>
</Project>");
        FixtureSolution.Write(dir, "A/A.csproj", @"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup>
  <ItemGroup>
    <PackageReference Include=""Newtonsoft.Json"" />
    <PackageReference Include=""Polly"" VersionOverride=""7.1.0"" />
  </ItemGroup>
</Project>");

        var tfm = Evaluate(dir, "A/A.csproj").GetProperty("frameworks")[0];

        Assert.True(tfm.GetProperty("cpmEnabled").GetBoolean());
        Assert.EndsWith("Directory.Packages.props", tfm.GetProperty("centralFile").GetString());
        var central = Item(tfm, "PackageVersion", "Newtonsoft.Json").GetProperty("versionSite");
        Assert.EndsWith("Directory.Packages.props", central.GetProperty("file").GetString());
        Assert.Equal("[12.0.1,14.0.0)", central.GetProperty("rawText").GetString());
        var over = Item(tfm, "PackageReference", "Polly").GetProperty("overrideSite");
        Assert.Equal("VersionOverride", over.GetProperty("name").GetString());
        Assert.Equal("7.1.0", over.GetProperty("rawText").GetString());
        Assert.Equal("3.6.0", Item(tfm, "GlobalPackageReference", "Nerdbank.GitVersioning").GetProperty("version").GetString());
    }

    [Fact]
    public void MixedPropertyValueIsASiteProblem()
    {
        var dir = FixtureSolution.NewDir();
        FixtureSolution.Write(dir, "M/M.csproj", @"<Project Sdk=""Microsoft.NET.Sdk"">
  <PropertyGroup><TargetFramework>net8.0</TargetFramework><Major>7</Major></PropertyGroup>
  <ItemGroup><PackageReference Include=""Polly"" Version=""$(Major).2.4"" /></ItemGroup>
</Project>");

        var item = Item(Evaluate(dir, "M/M.csproj").GetProperty("frameworks")[0], "PackageReference", "Polly");

        Assert.Equal("version mixes text and properties", item.GetProperty("siteProblem").GetString());
    }

    [Fact]
    public void NestedGlobalJsonWithOtherSdkIsAProjectError()
    {
        var dir = FixtureSolution.NewDir();
        FixtureSolution.Write(dir, "Old/global.json", @"{ ""sdk"": { ""version"": ""1.0.0"", ""rollForward"": ""disable"" } }");
        FixtureSolution.Write(dir, "Old/Old.csproj", @"<Project Sdk=""Microsoft.NET.Sdk""><PropertyGroup><TargetFramework>net8.0</TargetFramework></PropertyGroup></Project>");

        var p = Evaluate(dir, "Old/Old.csproj");

        Assert.Contains("global.json", p.GetProperty("error").GetString());
    }
}
