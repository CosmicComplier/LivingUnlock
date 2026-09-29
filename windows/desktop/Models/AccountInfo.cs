using System;

namespace LivingUnlock.Windows.Models;

public enum AccountType
{
    LocalUser,
    MicrosoftAccount
}

public class AccountInfo
{
    public AccountType Type { get; set; } = AccountType.LocalUser;
    public string Username { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;

    public string QualifiedUsername
    {
        get
        {
            if (Type == AccountType.MicrosoftAccount)
            {
                return Username.StartsWith("MicrosoftAccount\\", StringComparison.OrdinalIgnoreCase)
                    ? Username
                    : $"MicrosoftAccount\\{Username}";
            }
            return Username;
        }
    }
}

public class PairedPhoneInfo
{
    public string DeviceName { get; set; } = string.Empty;
    public string DeviceId { get; set; } = string.Empty;
    public string PcId { get; set; } = string.Empty;
    public string BluetoothMac { get; set; } = string.Empty;
    public DateTime PairedAt { get; set; } = DateTime.MinValue;
}
