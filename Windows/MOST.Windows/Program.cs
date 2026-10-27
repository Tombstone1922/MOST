using System;
using System.IO;
using System.Threading;
using System.Windows;
using Most.Windows.Core;
using Most.Windows.Views;

namespace Most.Windows;

public static class Program
{
    [STAThread]
    public static void Main()
    {
        using var mutex = new Mutex(true, @"Global\MOST.Windows", out var first);
        if (!first) { MessageBox.Show("MOST уже запущен. Найдите его окно или значок в области уведомлений.", "MOST"); return; }
        NativeCore.PrepareConsole();
        var app = new Application { ShutdownMode = ShutdownMode.OnMainWindowClose };
        app.DispatcherUnhandledException += (_, e) => {
            MessageBox.Show("Не удалось выполнить операцию. Настройки подключения в сообщение не включены.\n" + e.Exception.GetType().Name, "MOST", MessageBoxButton.OK, MessageBoxImage.Warning);
            e.Handled = true;
        };
        var window = new MainWindow(); app.Run(window);
    }
}
