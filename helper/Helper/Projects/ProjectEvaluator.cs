using System.Collections.Concurrent;
using System.ComponentModel;
using System.Text.RegularExpressions;
using Microsoft.Build.Construction;
using Microsoft.Build.Definition;
using Microsoft.Build.Evaluation;
using Microsoft.Build.Evaluation.Context;
using Microsoft.Build.Locator;
using NuGetExtended.Core.Model;
using NuGetExtended.Helper.FileApps;

namespace NuGetExtended.Helper.Projects;

public sealed class ProjectEvaluator
{
    private static readonly string[] ItemTypes = { "PackageReference", "PackageVersion", "GlobalPackageReference" };
    private static readonly Regex SingleProperty = new(@"^\s*\$\(([A-Za-z_][A-Za-z0-9_.-]*)\)\s*$", RegexOptions.Compiled);

    private readonly string _dotnetRoot;
    private readonly string _sdkVersion;
    private readonly ConcurrentDictionary<string, EvaluatedProject> _cache = new(StringComparer.OrdinalIgnoreCase);
    private EvaluationContext _context = EvaluationContext.Create(EvaluationContext.SharingPolicy.Shared);
    // Bumped by every Invalidate, so an Evaluate in flight does not keep a stale result.
    private int _generation;

    public ProjectEvaluator(string dotnetRoot, string sdkVersion)
    {
        _dotnetRoot = Path.GetFullPath(dotnetRoot);
        _sdkVersion = sdkVersion;
    }

    /// [onEvaluated] gets the count done so far after each project (from worker threads).
    public IReadOnlyList<EvaluatedProject> EvaluateAll(IReadOnlyList<string> paths, string runtime, CancellationToken ct, Action<int>? onEvaluated = null)
    {
        var results = new EvaluatedProject[paths.Count];
        var workers = Math.Clamp(Environment.ProcessorCount - 1, 1, 8);
        var done = 0;
        Parallel.For(0, paths.Count, new ParallelOptions { MaxDegreeOfParallelism = workers, CancellationToken = ct },
            () => new ProjectCollection(),
            (i, _, collection) =>
            {
                results[i] = Evaluate(paths[i], runtime, collection, ct);
                onEvaluated?.Invoke(Interlocked.Increment(ref done));
                return collection;
            },
            collection => collection.Dispose());
        return results;
    }

    public EvaluatedProject Evaluate(string path, string runtime)
    {
        using var collection = new ProjectCollection();
        return Evaluate(path, runtime, collection, CancellationToken.None);
    }

    /// "*" drops everything (a new props/targets file may be imported by any project).
    public void Invalidate(IReadOnlyList<string> changedPaths)
    {
        // Bump before removing: an Evaluate that wrote after the removal then sees the new value.
        Interlocked.Increment(ref _generation);
        if (changedPaths.Contains("*"))
        {
            _cache.Clear();
            _context = EvaluationContext.Create(EvaluationContext.SharingPolicy.Shared);
            return;
        }
        var changed = new HashSet<string>(
            changedPaths.Where(p => !string.IsNullOrWhiteSpace(p)).Select(Path.GetFullPath),
            StringComparer.OrdinalIgnoreCase);
        var removed = false;
        foreach (var (key, value) in _cache)
            if (value.Inputs.Any(changed.Contains) || changed.Contains(key.Split('|')[0]))
                removed |= _cache.TryRemove(key, out _);
        // A new context drops MSBuild's cached file state; only needed when something we used changed.
        if (removed) _context = EvaluationContext.Create(EvaluationContext.SharingPolicy.Shared);
    }

    private EvaluatedProject Evaluate(string path, string runtime, ProjectCollection collection, CancellationToken ct = default)
    {
        path = Path.GetFullPath(path);
        var key = path + "|" + runtime;
        if (_cache.TryGetValue(key, out var cached)) return cached;

        if (path.EndsWith(".cs", StringComparison.OrdinalIgnoreCase))
            return EvaluateFileBasedApp(path, key, ct);

        var name = Path.GetFileNameWithoutExtension(path);
        var sdkError = CheckSdk(path);
        if (sdkError != null) return new EvaluatedProject(path, name, Array.Empty<EvaluatedTfm>(), new[] { path }, sdkError);

        // One context per call, so outer and inner evaluations agree even if Invalidate swaps it.
        var generation = Volatile.Read(ref _generation);
        var context = _context;

        try
        {
            var global = new Dictionary<string, string>();
            if (!string.IsNullOrWhiteSpace(runtime)) global["RuntimeIdentifier"] = runtime.Trim();
            var outer = Load(path, global, collection, context);
            var inputs = new HashSet<string>(StringComparer.OrdinalIgnoreCase) { path };
            var frameworks = new List<EvaluatedTfm>();

            var multi = outer.GetPropertyValue("TargetFrameworks");
            var tfms = multi.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
            if (tfms.Length == 0)
            {
                // Single target: Extract(outer, ...) below adds outer's own imports already.
                frameworks.Add(Extract(outer, outer.GetPropertyValue("TargetFramework"), inputs));
            }
            else
            {
                // Multi-target: outer is never passed to Extract, so its imports need adding here.
                AddImports(outer, inputs);
                foreach (var tfm in tfms)
                {
                    var props = new Dictionary<string, string>(global) { ["TargetFramework"] = tfm, ["TargetFrameworks"] = "" };
                    var inner = Load(path, props, collection, context);
                    frameworks.Add(Extract(inner, tfm, inputs));
                    collection.UnloadProject(inner);
                }
            }
            collection.UnloadProject(outer);

            var result = new EvaluatedProject(path, name, frameworks, inputs.ToList(), null);
            // Write, then re-check: if an Invalidate started meanwhile, drop only this entry.
            _cache[key] = result;
            if (Volatile.Read(ref _generation) != generation)
                _cache.TryRemove(new KeyValuePair<string, EvaluatedProject>(key, result));
            return result;
        }
        catch (Microsoft.Build.Exceptions.InvalidProjectFileException e)
        {
            return new EvaluatedProject(path, name, Array.Empty<EvaluatedTfm>(), new[] { path }, e.BaseMessage);
        }
    }

    /// A `.cs` file-based app (SDK 10+): no MSBuild project to load, so it never touches
    /// `collection`/`context` - `dotnet build -getProperty` does its own evaluation out of process.
    /// On SDK &lt; 10 file-based apps do not exist: the path is ignored (no rows, no error), same as
    /// [Handlers.Scan] already drops it up front when the caller has not opted in.
    private EvaluatedProject EvaluateFileBasedApp(string path, string key, CancellationToken ct)
    {
        var name = Path.GetFileNameWithoutExtension(path);
        if (Version.Parse(_sdkVersion.Split('-')[0]).Major < 10)
            return new EvaluatedProject(path, name, Array.Empty<EvaluatedTfm>(), new[] { path }, null);

        // Same write-then-re-check pattern as the MSBuild path below: snapshot the generation before
        // the (slow, out-of-process) evaluation, and if an Invalidate ran meanwhile, drop only this entry.
        var generation = Volatile.Read(ref _generation);
        EvaluatedProject result;
        try
        {
            result = FileBasedApps.Evaluate(path, _dotnetRoot, ct);
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or Win32Exception)
        {
            // The file or its folder is gone or unreadable, or dotnet cannot start: a project error
            // (like InvalidProjectFileException below), not a bug. Not cached.
            return new EvaluatedProject(path, name, Array.Empty<EvaluatedTfm>(), new[] { path }, e.Message.Split('\n')[0].Trim());
        }
        _cache[key] = result;
        if (Volatile.Read(ref _generation) != generation)
            _cache.TryRemove(new KeyValuePair<string, EvaluatedProject>(key, result));
        return result;
    }

    private static Project Load(string path, Dictionary<string, string> global, ProjectCollection collection, EvaluationContext context) =>
        Project.FromFile(path, new ProjectOptions
        {
            GlobalProperties = global,
            ProjectCollection = collection,
            EvaluationContext = context,
            LoadSettings = ProjectLoadSettings.IgnoreMissingImports | ProjectLoadSettings.IgnoreInvalidImports,
        });

    private void AddImports(Project p, HashSet<string> inputs)
    {
        var packagesRoot = p.GetPropertyValue("NuGetPackageRoot");
        foreach (var import in p.Imports)
        {
            var file = import.ImportedProject.FullPath;
            if (IsUnder(file, _dotnetRoot) || (packagesRoot.Length > 0 && IsUnder(file, packagesRoot))) continue;
            inputs.Add(file);
        }
    }

    private EvaluatedTfm Extract(Project p, string tfm, HashSet<string> inputs)
    {
        AddImports(p, inputs);

        var items = new List<PackageItem>();
        foreach (var type in ItemTypes)
        {
            foreach (var item in p.GetItems(type))
            {
                var (vSite, vProblem) = Site(p, item, "Version");
                var (oSite, oProblem) = Site(p, item, "VersionOverride");
                items.Add(new PackageItem(
                    item.EvaluatedInclude,
                    type,
                    NullIfEmpty(item.GetMetadataValue("Version")),
                    NullIfEmpty(item.GetMetadataValue("VersionOverride")),
                    vSite,
                    oSite,
                    oProblem ?? vProblem));
            }
        }

        // Not merged: RestoreSources replaces the NuGet.config sources, RestoreAdditionalProjectSources
        // adds to them. The SDK leaves RestoreSources empty and fills only the additional one (its
        // library-packs folder), so a normal project keeps its config sources.
        var sources = SplitList(p.GetPropertyValue("RestoreSources"));
        var additional = SplitList(p.GetPropertyValue("RestoreAdditionalProjectSources"));

        return new EvaluatedTfm(
            tfm,
            p.GetPropertyValue("ProjectAssetsFile"),
            IsTrue(p.GetPropertyValue("ManagePackageVersionsCentrally")),
            IsTrue(p.GetPropertyValue("CentralPackageTransitivePinningEnabled")),
            NullIfEmpty(p.GetPropertyValue("DirectoryPackagesPropsPath")),
            items,
            sources,
            NullIfEmpty(p.GetPropertyValue("NuGetPackageRoot")),
            additional);
    }

    private static List<string> SplitList(string value) =>
        value.Split(';', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries).ToList();

    private static (ValueSite?, string?) Site(Project p, ProjectItem item, string metadataName)
    {
        var m = item.GetMetadata(metadataName);
        if (m == null) return (null, null);
        if (m.Xml?.Parent is not ProjectItemElement owner) return (null, "version comes from an item definition");
        // A child element can carry its own Condition (an attribute cannot). The site only records
        // the owner item's condition, and the edit takes the first matching child, so it could hit
        // the wrong element.
        if (m.Xml.Condition.Length > 0) return (null, "the version element has its own condition - edit it by hand");

        var identityAttr = owner.Include.Length > 0 ? "Include" : "Update";
        var identity = owner.Include.Length > 0 ? owner.Include : owner.Update;
        var groupCondition = NullIfEmpty(owner.Parent?.Condition);
        var raw = m.UnevaluatedValue;

        var single = SingleProperty.Match(raw);
        if (single.Success)
        {
            var prop = p.GetProperty(single.Groups[1].Value);
            if (prop == null || prop.Xml == null || prop.IsEnvironmentProperty || prop.IsGlobalProperty || prop.IsReservedProperty)
                return (null, $"version comes from property '{single.Groups[1].Value}', which is not set in a project file");
            if (prop.UnevaluatedValue.Contains("$("))
                return (null, $"property '{prop.Name}' points to another property");
            var loc = prop.Xml.Location;
            return (new ValueSite("property", prop.Xml.ContainingProject.FullPath, loc.Line, loc.Column, prop.Name, "property",
                prop.UnevaluatedValue, null, null, null, NullIfEmpty(prop.Xml.Condition), NullIfEmpty(prop.Xml.Parent?.Condition)), null);
        }
        if (raw.Contains("$(")) return (null, "version mixes text and properties");

        var ownerLoc = owner.Location;
        return (new ValueSite("metadata", owner.ContainingProject.FullPath, ownerLoc.Line, ownerLoc.Column, metadataName,
            m.Xml.ExpressedAsAttribute ? "attribute" : "child", raw, owner.ItemType, identityAttr, identity,
            NullIfEmpty(owner.Condition), groupCondition), null);
    }

    private string? CheckSdk(string projectPath)
    {
        var dir = Path.GetDirectoryName(projectPath)!;
        Microsoft.Build.Locator.VisualStudioInstance? inst;
        try
        {
            inst = MSBuildLocator.QueryVisualStudioInstances(new VisualStudioInstanceQueryOptions
            {
                DiscoveryTypes = DiscoveryType.DotNetSdk,
                WorkingDirectory = dir,
            }).FirstOrDefault();
        }
        catch (InvalidOperationException e)
        {
            // A global.json under this project's dir names an SDK version hostfxr cannot resolve
            // (not installed): MSBuildLocator throws instead of returning an empty list here.
            // Stdout carries only protocol JSON, so the raw locator message goes to stderr.
            Console.Error.WriteLine(e.Message);
            inst = null;
        }
        if (inst == null) return "This project's global.json selects an SDK that is not installed.";
        return inst.Version.ToString() == _sdkVersion
            ? null
            : $"This project's global.json selects SDK {inst.Version}; the solution uses {_sdkVersion}.";
    }

    private static bool IsUnder(string file, string root) => PathRules.IsUnder(file, root);

    private static bool IsTrue(string v) => string.Equals(v.Trim(), "true", StringComparison.OrdinalIgnoreCase);
    private static string? NullIfEmpty(string? v) => string.IsNullOrEmpty(v) ? null : v;
}
