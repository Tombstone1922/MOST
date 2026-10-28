using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Linq;

namespace Most.Windows.Core;

public sealed record DesktopApp(string Name, string Path);
public static class AppCatalog
{
    // Windows has no Android-like package inventory: running executables provide exact routing paths.
    public static IReadOnlyList<DesktopApp> Running()
    {
        var result = new Dictionary<string, DesktopApp>(StringComparer.OrdinalIgnoreCase);
        foreach (var process in Process.GetProcesses()) {
            using (process) {
                try {
                    var path = process.MainModule?.FileName;
                    if (string.IsNullOrWhiteSpace(path) || !path.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) ||
                        path.Equals(Environment.ProcessPath, StringComparison.OrdinalIgnoreCase) || Path.GetFileName(path).Equals("xray.exe", StringComparison.OrdinalIgnoreCase)) continue;
                    var name = FileVersionInfo.GetVersionInfo(path).FileDescription;
                    result.TryAdd(path, new(string.IsNullOrWhiteSpace(name) ? Path.GetFileNameWithoutExtension(path) : name, path));
                } catch (Win32Exception) { } catch (InvalidOperationException) { } catch (IOException) { } catch (UnauthorizedAccessException) { }
            }
        }
        return result.Values.OrderBy(x => x.Name, StringComparer.CurrentCultureIgnoreCase).ToArray();
    }
}
