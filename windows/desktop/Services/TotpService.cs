using System;
using System.Security.Cryptography;
using System.Text;

namespace LivingUnlock.Windows.Services;

public static class TotpService
{
    private const string Base32Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    public static byte[] GenerateRandomSecret(int length = 20)
    {
        byte[] bytes = new byte[length];
        RandomNumberGenerator.Fill(bytes);
        return bytes;
    }

    public static string ToBase32(byte[] data)
    {
        if (data == null || data.Length == 0) return string.Empty;

        StringBuilder sb = new StringBuilder();
        int buffer = 0;
        int bitsLeft = 0;

        foreach (byte b in data)
        {
            buffer = (buffer << 8) | b;
            bitsLeft += 8;
            while (bitsLeft >= 5)
            {
                bitsLeft -= 5;
                int index = (buffer >> bitsLeft) & 31;
                sb.Append(Base32Alphabet[index]);
            }
        }

        if (bitsLeft > 0)
        {
            int index = (buffer << (5 - bitsLeft)) & 31;
            sb.Append(Base32Alphabet[index]);
        }

        return sb.ToString();
    }

    public static byte[] FromBase32(string base32)
    {
        if (string.IsNullOrWhiteSpace(base32)) return Array.Empty<byte>();

        string clean = base32.Trim().ToUpperInvariant().Replace(" ", "").Replace("-", "");
        var output = new System.Collections.Generic.List<byte>();
        int buffer = 0;
        int bitsLeft = 0;

        foreach (char c in clean)
        {
            int val = Base32Alphabet.IndexOf(c);
            if (val < 0) continue;

            buffer = (buffer << 5) | val;
            bitsLeft += 5;
            if (bitsLeft >= 8)
            {
                bitsLeft -= 8;
                output.Add((byte)((buffer >> bitsLeft) & 0xFF));
            }
        }

        return output.ToArray();
    }

    public static string ComputeTotp(byte[] secret, long unixTimeSec, int digits = 6, int period = 30)
    {
        long step = unixTimeSec / period;
        byte[] counter = BitConverter.GetBytes(step);
        if (BitConverter.IsLittleEndian)
        {
            Array.Reverse(counter);
        }

        using var hmac = new HMACSHA1(secret);
        byte[] hash = hmac.ComputeHash(counter);

        int offset = hash[^1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24) |
                     ((hash[offset + 1] & 0xFF) << 16) |
                     ((hash[offset + 2] & 0xFF) << 8) |
                     (hash[offset + 3] & 0xFF);

        int pin = binary % (int)Math.Pow(10, digits);
        return pin.ToString().PadLeft(digits, '0');
    }

    public static bool VerifyTotp(byte[] secret, string code, out long matchedStep, int toleranceSteps = 1, int period = 30)
    {
        matchedStep = 0;
        if (string.IsNullOrWhiteSpace(code) || code.Length != 6) return false;

        long now = DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        long currentStep = now / period;

        for (int i = -toleranceSteps; i <= toleranceSteps; i++)
        {
            long checkStep = currentStep + i;
            long checkTime = checkStep * period;
            string computed = ComputeTotp(secret, checkTime, 6, period);
            if (computed.Equals(code.Trim(), StringComparison.Ordinal))
            {
                matchedStep = checkStep;
                return true;
            }
        }

        return false;
    }

    public static string FormatProvisioningUri(string accountName, string base32Secret, string issuer = "LivingUnlock")
    {
        string encodedAccount = Uri.EscapeDataString(accountName);
        string encodedIssuer = Uri.EscapeDataString(issuer);
        return $"otpauth://totp/{encodedIssuer}:{encodedAccount}?secret={base32Secret}&issuer={encodedIssuer}&digits=6&period=30";
    }
}
