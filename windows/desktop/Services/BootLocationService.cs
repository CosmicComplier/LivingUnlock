using System;
using System.Globalization;
using System.Threading.Tasks;
using Microsoft.Win32;
using Windows.Devices.Geolocation;

namespace LivingUnlock.Windows.Services;

public static class BootLocationService
{
    private const string SettingsKey = @"Software\LivingUnlock";
    private const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    private const string StartupName = "LivingUnlockLocation";
    public static bool Enabled
    {
        get { using var key = Registry.CurrentUser.OpenSubKey(SettingsKey); return (key?.GetValue("RecordBootLocation") as long?) == 1; }
    }

    public static async Task EnableAsync()
    {
        if (await Geolocator.RequestAccessAsync() != GeolocationAccessStatus.Allowed)
            throw new InvalidOperationException("Windows 未允许读取位置，请在系统“隐私和安全性 → 位置”中允许后重试。");
        var executable = Environment.ProcessPath ?? throw new InvalidOperationException("无法确定程序路径。");
        using var run = Registry.CurrentUser.CreateSubKey(RunKey);
        using var settings = Registry.CurrentUser.CreateSubKey(SettingsKey);
        run.SetValue(StartupName, $"\"{executable}\" --capture-boot-location", RegistryValueKind.String);
        settings.SetValue("RecordBootLocation", 1L, RegistryValueKind.QWord);
    }

    public static void Disable()
    {
        using var run = Registry.CurrentUser.OpenSubKey(RunKey, true);
        run?.DeleteValue(StartupName, false);
        using var settings = Registry.CurrentUser.CreateSubKey(SettingsKey);
        settings.SetValue("RecordBootLocation", 0L, RegistryValueKind.QWord);
        foreach (var value in new[] { "BootLocation", "LocationCapturedMs", "LocationBootMs" }) settings.DeleteValue(value, false);
    }

    // Runs only from the opt-in login entry. Never requests permission in background.
    public static async Task CaptureAtLoginAsync()
    {
        if (!Enabled) return;
        using var settings = Registry.CurrentUser.CreateSubKey(SettingsKey);
        foreach (var name in new[] { "BootLocation", "LocationCapturedMs", "LocationBootMs" }) settings.DeleteValue(name, false);
        try
        {
            var locator = new Geolocator { DesiredAccuracy = PositionAccuracy.Default };
            var position = await locator.GetGeopositionAsync(TimeSpan.Zero, TimeSpan.FromSeconds(25));
            if (!Enabled) return;
            var now = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
            var p = position.Coordinate.Point.Position;
            var value = string.Format(CultureInfo.InvariantCulture, "{0:F5}, {1:F5} (±{2:F0} m)", p.Latitude, p.Longitude, position.Coordinate.Accuracy);
            settings.SetValue("BootLocation", value, RegistryValueKind.String);
            settings.SetValue("LocationCapturedMs", position.Coordinate.Timestamp.ToUnixTimeMilliseconds(), RegistryValueKind.QWord);
            settings.SetValue("LocationBootMs", now - Environment.TickCount64, RegistryValueKind.QWord);
        }
        catch { /* No fix or permission revoked: leave explicitly unrecorded. */ }
    }
}
