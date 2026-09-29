using System;
using System.ComponentModel;
using System.Diagnostics;
using System.IO;
using System.Security.Principal;
using System.Threading.Tasks;

namespace LivingUnlock.Windows.Services;

public readonly record struct UnlockState(
    bool AuthenticatorConfigured, bool PhoneConfigured,
    bool AuthenticatorEnabled, bool PhoneEnabled);

public static class UnlockConfigurationManager
{
    private static string ScriptPath => Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles),
        "WindowsLockPin", "tools", "Manage-LivingUnlock.ps1");

    private static async Task<int> ExecuteAsync(string action)
    {
        if (!File.Exists(ScriptPath))
            throw new FileNotFoundException("未找到已安装的 LivingUnlock 系统管理组件。请先安装或更新锁屏组件。", ScriptPath);

        string sid = WindowsIdentity.GetCurrent().User?.Value
            ?? throw new InvalidOperationException("无法确定当前 Windows 用户。");
        var start = new ProcessStartInfo
        {
            FileName = Path.Combine(Environment.SystemDirectory, "WindowsPowerShell", "v1.0", "powershell.exe"),
            UseShellExecute = true,
            Verb = "runas",
            WindowStyle = ProcessWindowStyle.Hidden
        };
        start.ArgumentList.Add("-NoProfile");
        start.ArgumentList.Add("-NonInteractive");
        start.ArgumentList.Add("-ExecutionPolicy");
        start.ArgumentList.Add("Bypass");
        start.ArgumentList.Add("-File");
        start.ArgumentList.Add(ScriptPath);
        start.ArgumentList.Add("-Action");
        start.ArgumentList.Add(action);
        start.ArgumentList.Add("-UserSid");
        start.ArgumentList.Add(sid);

        try
        {
            using var process = Process.Start(start)
                ?? throw new InvalidOperationException("无法启动系统管理组件。");
            await process.WaitForExitAsync();
            return process.ExitCode;
        }
        catch (Win32Exception ex) when (ex.NativeErrorCode == 1223)
        {
            throw new InvalidOperationException("未获得管理员确认，设置没有改变。", ex);
        }
    }

    public static async Task<UnlockState> ReadStateAsync()
    {
        int code = await ExecuteAsync("status");
        if (code is < 0 or > 15) throw new InvalidOperationException("无法读取锁屏组件的启用状态。");
        return new UnlockState((code & 1) != 0, (code & 2) != 0,
            (code & 4) != 0, (code & 8) != 0);
    }

    public static async Task RunAsync(string action)
    {
        if (action is not ("promote-pair" or "promote-phone" or "promote-auth" or "promote-credentials"
            or "disable-phone" or "enable-phone" or "disable-auth" or "enable-auth"
            or "remove-phone" or "remove-auth" or "remove-both"))
            throw new ArgumentOutOfRangeException(nameof(action));
        if (await ExecuteAsync(action) != 0)
            throw new InvalidOperationException("系统管理组件未能完成操作，锁屏设置可能未改变。");
    }
}
