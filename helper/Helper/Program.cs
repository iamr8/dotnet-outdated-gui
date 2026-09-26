using System.Text;
using System.Text.Json;
using NuGetExtended.Core.Protocol;

namespace NuGetExtended.Helper;

public static class Program
{
    public static async Task<int> Main(string[] args)
    {
        // Protocol owns the real stdout. Any stray Console.Write (ours or a library's) goes to stderr.
        var protocolOut = new StreamWriter(Console.OpenStandardOutput(), new UTF8Encoding(false)) { AutoFlush = true };
        Console.SetOut(Console.Error);
        var input = new StreamReader(Console.OpenStandardInput(), new UTF8Encoding(false));

        var workDir = args.Length > 0 ? args[0] : Directory.GetCurrentDirectory();
        Hello hello;
        try
        {
            hello = SdkHost.Register(workDir);
        }
        catch (SdkException e)
        {
            await protocolOut.WriteLineAsync(JsonSerializer.Serialize(new EventLine("fatal", new Fatal(e.Message)), Json.Options));
            return 2;
        }

        await protocolOut.WriteLineAsync(JsonSerializer.Serialize(new EventLine("hello", hello), Json.Options));
        return await App.RunAsync(hello, input, protocolOut);
    }
}
