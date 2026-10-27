using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text.Json;

namespace Most.Windows.Core;

/// Windows state has a separate owner from Android MMKV; credentials are DPAPI-protected per user.
public sealed class Storage
{
    public static readonly string Root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "MOST", "Windows");
    private readonly string _file;
    public static readonly JsonSerializerOptions Json = new() { IgnoreReadOnlyProperties = true };
    public Storage(string? file = null) => _file = file ?? Path.Combine(Root, "state.bin");
    public AppState Load()
    {
        if (!File.Exists(_file)) { var initial = new AppState(); Save(initial); return initial; }
        try { return JsonSerializer.Deserialize<AppState>(Protect(File.ReadAllBytes(_file), false), Json) ?? throw new UserError("Пустое хранилище настроек."); }
        catch (Exception e) when (e is not UserError) { throw new UserError("Не удалось открыть сохранённые настройки Windows. Файл не перезаписан. Возможно, он принадлежит другому пользователю Windows."); }
    }
    public void Save(AppState state)
    {
        var bytes = Protect(JsonSerializer.SerializeToUtf8Bytes(state, Json), true);
        Directory.CreateDirectory(Path.GetDirectoryName(_file)!);
        var temp = _file + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try { File.WriteAllBytes(temp, bytes); File.Move(temp, _file, true); }
        finally { if (File.Exists(temp)) File.Delete(temp); }
    }
    public static AppState Clone(AppState state) => JsonSerializer.Deserialize<AppState>(JsonSerializer.Serialize(state, Json), Json)!;
    [StructLayout(LayoutKind.Sequential)] private struct Blob { public int Size; public IntPtr Data; }
    [DllImport("crypt32.dll", CharSet = CharSet.Unicode, SetLastError = true)] private static extern bool CryptProtectData(ref Blob data, string description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("crypt32.dll", SetLastError = true)] private static extern bool CryptUnprotectData(ref Blob data, out IntPtr description, IntPtr entropy, IntPtr reserved, IntPtr prompt, int flags, out Blob output);
    [DllImport("kernel32.dll")] private static extern IntPtr LocalFree(IntPtr pointer);
    private static byte[] Protect(byte[] bytes, bool encrypt)
    {
        var input = new Blob { Size = bytes.Length, Data = Marshal.AllocHGlobal(bytes.Length) };
        Marshal.Copy(bytes, 0, input.Data, bytes.Length);
        Blob output = default; IntPtr description = IntPtr.Zero;
        try {
            var ok = encrypt ? CryptProtectData(ref input, "MOST Windows", IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output)
                : CryptUnprotectData(ref input, out description, IntPtr.Zero, IntPtr.Zero, IntPtr.Zero, 1, out output);
            if (!ok) throw new UserError("Windows не смог защитить или открыть настройки приложения.");
            var result = new byte[output.Size]; Marshal.Copy(output.Data, result, 0, output.Size); return result;
        } finally { Marshal.FreeHGlobal(input.Data); if (output.Data != IntPtr.Zero) LocalFree(output.Data); if (description != IntPtr.Zero) LocalFree(description); }
    }
}
