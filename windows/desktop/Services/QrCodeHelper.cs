using System;
using System.IO;
using System.Threading.Tasks;
using Microsoft.UI.Xaml.Media.Imaging;
using QRCoder;
using Windows.Storage.Streams;

namespace LivingUnlock.Windows.Services;

public static class QrCodeHelper
{
    public static async Task<BitmapImage> GenerateQrBitmapAsync(string content, int pixelsPerModule = 10)
    {
        using var generator = new QRCodeGenerator();
        using var data = generator.CreateQrCode(content, QRCodeGenerator.ECCLevel.M);
        var qrCode = new PngByteQRCode(data);
        byte[] pngBytes = qrCode.GetGraphic(pixelsPerModule);

        var bitmapImage = new BitmapImage();
        using var ms = new InMemoryRandomAccessStream();
        using (var writer = new DataWriter(ms.GetOutputStreamAt(0)))
        {
            writer.WriteBytes(pngBytes);
            await writer.StoreAsync();
        }
        await bitmapImage.SetSourceAsync(ms);
        return bitmapImage;
    }
}
