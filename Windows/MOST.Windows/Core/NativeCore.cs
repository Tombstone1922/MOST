using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text.Json.Nodes;
using System.Threading;
using System.Threading.Tasks;

namespace Most.Windows.Core;

internal static class NativeCore
{
    public static readonly string DirectoryPath = Path.Combine(AppContext.BaseDirectory, "core");
    public static string Executable => Path.Combine(DirectoryPath, "xray.exe");
    private static bool _ownConsole;
    private delegate bool ControlHandler(uint signal);
    private static readonly ControlHandler Handler = _ => true;
    public static void PrepareConsole()
    {
        // A private hidden console lets Go receive Ctrl+C and flush TUN routes/DNS on normal stop.
        // Never broadcast to the user's PowerShell console.
        FreeConsole(); _ownConsole = AllocConsole();
        if (_ownConsole) { ShowWindow(GetConsoleWindow(), 0); SetConsoleCtrlHandler(Handler, true); }
    }
    public static void RequestGracefulStop() { if (_ownConsole) GenerateConsoleCtrlEvent(0, 0); }
    public static async Task VerifyAsync(CancellationToken ct)
    {
        var file = Path.Combine(DirectoryPath, "manifest.json");
        if (!File.Exists(file)) throw new UserError("Нет папки core. Распакуйте весь комплект MOST или установите приложение заново.");
        var hashes = JsonNode.Parse(await File.ReadAllTextAsync(file, ct))!.AsObject();
        foreach (var name in new[] { "xray.exe", "wintun.dll" }) {
            var path = Path.Combine(DirectoryPath, name);
            if (!File.Exists(path)) throw new UserError($"Не найден компонент {name}. Переустановите MOST.");
            await using var stream = File.OpenRead(path);
            var digest = Convert.ToHexString(await SHA256.HashDataAsync(stream, ct));
            if (!digest.Equals(hashes[name]?.GetValue<string>(), StringComparison.OrdinalIgnoreCase)) throw new UserError($"Контрольная сумма {name} не совпала. Переустановите MOST из исходного комплекта.");
        }
    }
    public static Process Start(string config, bool test)
    {
        var info = new ProcessStartInfo(Executable) { UseShellExecute = false, CreateNoWindow = false,
            WindowStyle = ProcessWindowStyle.Hidden, WorkingDirectory = DirectoryPath, RedirectStandardOutput = true, RedirectStandardError = true };
        info.ArgumentList.Add("run"); if (test) info.ArgumentList.Add("-test");
        info.ArgumentList.Add("-config"); info.ArgumentList.Add(config);
        return Process.Start(info) ?? throw new UserError("Не удалось запустить Xray.");
    }
    [DllImport("kernel32.dll")] private static extern bool AllocConsole();
    [DllImport("kernel32.dll")] private static extern bool FreeConsole();
    [DllImport("kernel32.dll")] private static extern IntPtr GetConsoleWindow();
    [DllImport("user32.dll")] private static extern bool ShowWindow(IntPtr window, int command);
    [DllImport("kernel32.dll")] private static extern bool GenerateConsoleCtrlEvent(uint signal, uint group);
    [DllImport("kernel32.dll")] private static extern bool SetConsoleCtrlHandler(ControlHandler handler, bool add);
}

/// Killing the UI cannot leave an unowned core process running in the background.
internal sealed class CoreJob : IDisposable
{
    private IntPtr _handle;
    public CoreJob()
    {
        _handle = CreateJobObject(IntPtr.Zero, null);
        if (_handle == IntPtr.Zero) throw new Win32Exception(Marshal.GetLastWin32Error());
        var limits = new ExtendedLimits { Basic = new BasicLimits { Flags = 0x2000 } };
        var size = Marshal.SizeOf<ExtendedLimits>(); var ptr = Marshal.AllocHGlobal(size);
        try {
            Marshal.StructureToPtr(limits, ptr, false);
            if (!SetInformationJobObject(_handle, 9, ptr, (uint)size)) throw new Win32Exception(Marshal.GetLastWin32Error());
        } catch { Dispose(); throw; }
        finally { Marshal.FreeHGlobal(ptr); }
    }
    public void Add(Process process)
    {
        if (!AssignProcessToJobObject(_handle, process.Handle)) {
            if (!process.HasExited) process.Kill(true);
            throw new UserError("Windows не разрешил управлять процессом Xray. Подключение остановлено.");
        }
    }
    public void Dispose() { if (_handle != IntPtr.Zero) { CloseHandle(_handle); _handle = IntPtr.Zero; } }
    [StructLayout(LayoutKind.Sequential)] private struct BasicLimits {
        public long ProcessTime, JobTime; public uint Flags; public UIntPtr MinWorking, MaxWorking;
        public uint ActiveProcessLimit; public UIntPtr Affinity; public uint Priority, Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)] private struct IoCounters { public ulong ReadOp, WriteOp, OtherOp, ReadBytes, WriteBytes, OtherBytes; }
    [StructLayout(LayoutKind.Sequential)] private struct ExtendedLimits {
        public BasicLimits Basic; public IoCounters Io; public UIntPtr ProcessMemory, JobMemory, PeakProcessMemory, PeakJobMemory;
    }
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern IntPtr CreateJobObject(IntPtr attrs, string? name);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool SetInformationJobObject(IntPtr job, int kind, IntPtr info, uint length);
    [DllImport("kernel32.dll", SetLastError = true)] private static extern bool AssignProcessToJobObject(IntPtr job, IntPtr process);
    [DllImport("kernel32.dll")] private static extern bool CloseHandle(IntPtr handle);
}
