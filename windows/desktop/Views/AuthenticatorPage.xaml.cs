using System;
using System.Linq;
using System.Threading.Tasks;
using LivingUnlock.Windows.Models;
using LivingUnlock.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Windows.ApplicationModel.DataTransfer;

namespace LivingUnlock.Windows.Views;

public sealed partial class AuthenticatorPage : Page
{
    private AccountInfo _account = new();
    private byte[] _secret = Array.Empty<byte>();
    private string _base32Secret = string.Empty;

    public AuthenticatorPage() => InitializeComponent();

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is AccountInfo account) _account = account;
        TxtTargetAccount.Text = _account.QualifiedUsername;
        await GenerateNewSecretAndQrAsync();
    }

    private void OnPageLoaded(object sender, RoutedEventArgs e)
    {
        PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1080);
        Motion.Enter(BindingPanel, 0, 18);
        Motion.Enter(VerifyPanel, 65, 18);
    }

    private void OnScrollViewportSizeChanged(object sender, SizeChangedEventArgs e)
        => PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1080);

    private async Task GenerateNewSecretAndQrAsync()
    {
        if (_secret.Length > 0) Array.Clear(_secret, 0, _secret.Length);
        _secret = TotpService.GenerateRandomSecret(20);
        _base32Secret = TotpService.ToBase32(_secret);
        TxtSecretDisplay.Text = GroupSecret(_base32Secret);
        var label = string.IsNullOrWhiteSpace(_account.Username) ? Environment.UserName : _account.Username;
        var uri = TotpService.FormatProvisioningUri(label, _base32Secret, "LivingUnlock");
        try
        {
            ImgQrCode.Source = await QrCodeHelper.GenerateQrBitmapAsync(uri, 8);
        }
        catch (Exception ex)
        {
            ShowResult("二维码生成失败", ex.Message, InfoBarSeverity.Error);
        }
    }

    private static string GroupSecret(string secret)
    {
        var chunks = new System.Collections.Generic.List<string>();
        for (var i = 0; i < secret.Length; i += 4)
            chunks.Add(secret.Substring(i, Math.Min(4, secret.Length - i)));
        return string.Join(" ", chunks);
    }

    private void OnQrModeClicked(object sender, RoutedEventArgs e) => SetMode(true);
    private void OnKeyModeClicked(object sender, RoutedEventArgs e) => SetMode(false);

    private void SetMode(bool qr)
    {
        QrModePanel.Visibility = qr ? Visibility.Visible : Visibility.Collapsed;
        KeyModePanel.Visibility = qr ? Visibility.Collapsed : Visibility.Visible;
        BtnQrMode.Background = new SolidColorBrush(qr
            ? global::Windows.UI.Color.FromArgb(255, 36, 152, 243)
            : global::Windows.UI.Color.FromArgb(0, 0, 0, 0));
        BtnKeyMode.Background = new SolidColorBrush(!qr
            ? global::Windows.UI.Color.FromArgb(255, 36, 152, 243)
            : global::Windows.UI.Color.FromArgb(0, 0, 0, 0));
        BtnQrMode.Foreground = new SolidColorBrush(qr ? Microsoft.UI.Colors.White : global::Windows.UI.Color.FromArgb(255, 167, 180, 193));
        BtnKeyMode.Foreground = new SolidColorBrush(!qr ? Microsoft.UI.Colors.White : global::Windows.UI.Color.FromArgb(255, 167, 180, 193));
        Motion.Enter(qr ? QrModePanel : KeyModePanel, 0, 10);
    }

    private async void OnRegenerateSecretClicked(object sender, RoutedEventArgs e)
    {
        TxtVerificationCode.Text = string.Empty;
        AuthResultBar.IsOpen = false;
        await GenerateNewSecretAndQrAsync();
    }

    private void OnCopySecretClicked(object sender, RoutedEventArgs e)
    {
        if (string.IsNullOrEmpty(_base32Secret)) return;
        var package = new DataPackage();
        package.SetText(_base32Secret);
        Clipboard.SetContent(package);
        BtnCopySecret.Content = "已复制";
        var timer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1.5) };
        timer.Tick += (_, _) => { BtnCopySecret.Content = "复制密钥"; timer.Stop(); };
        timer.Start();
    }

    private void OnVerificationCodeChanged(object sender, TextChangedEventArgs e)
    {
        var text = new string(TxtVerificationCode.Text.Where(char.IsDigit).Take(6).ToArray());
        if (text != TxtVerificationCode.Text)
        {
            TxtVerificationCode.Text = text;
            TxtVerificationCode.SelectionStart = text.Length;
        }
    }

    private async void OnVerifyAndSaveClicked(object sender, RoutedEventArgs e)
    {
        var code = TxtVerificationCode.Text.Trim();
        if (code.Length != 6)
        {
            ShowResult("请输入 6 位动态码", "从验证器读取当前验证码后再继续。", InfoBarSeverity.Warning);
            return;
        }
        if (!TotpService.VerifyTotp(_secret, code, out var matchedStep))
        {
            ShowResult("验证码不匹配", "等待验证码刷新，并确认手机和电脑时间同步。", InfoBarSeverity.Error);
            return;
        }

        var result = MainWindow.Current.Vault.SaveAuthenticatorEnrollment(_account, _secret, matchedStep);
        if (!result.Success)
        {
            ShowResult("保存失败", result.ErrorMessage, InfoBarSeverity.Error);
            return;
        }

        try
        {
            await UnlockConfigurationManager.RunAsync("promote-auth");
        }
        catch (Exception ex)
        {
            ShowResult("尚未在锁屏启用", ex.Message, InfoBarSeverity.Error);
            return;
        }

        BtnVerifyAndSave.IsEnabled = false;
        TxtVerificationCode.IsEnabled = false;
        ShowResult("Authenticator 已启用", "现在可在锁屏磁贴中输入 6 位动态码。", InfoBarSeverity.Success);
    }

    private void ShowResult(string title, string message, InfoBarSeverity severity)
    {
        AuthResultBar.Title = title;
        AuthResultBar.Message = message;
        AuthResultBar.Severity = severity;
        AuthResultBar.IsOpen = true;
    }

    private void OnBackClicked(object sender, RoutedEventArgs e) => MainWindow.Current.NavigateToHome();
}
