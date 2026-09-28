using System;
using System.Threading.Tasks;
using LivingUnlock.Windows.Models;
using LivingUnlock.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace LivingUnlock.Windows.Views;

public sealed partial class LivingUnlockPairPage : Page
{
    private AccountInfo _account = new();
    private readonly PairingProcessManager _pairingManager = new();
    private DispatcherTimer? _countdownTimer;
    private int _secondsLeft = 180;

    public LivingUnlockPairPage()
    {
        InitializeComponent();
        _pairingManager.PairingUriReceived += OnPairingUriReceived;
        _pairingManager.PairingSucceeded += OnPairingSucceeded;
        _pairingManager.PairingFailed += OnPairingFailed;
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        if (e.Parameter is AccountInfo account) _account = account;
        TxtTargetAccount.Text = _account.QualifiedUsername;
        await StartPairingSessionAsync();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        StopPairingSession();
        base.OnNavigatedFrom(e);
    }

    private void OnPageLoaded(object sender, RoutedEventArgs e)
    {
        PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1080);
        Motion.Enter(PairingPanel, 0, 18);
        Motion.Enter(GuidePanel, 65, 18);
    }

    private void OnScrollViewportSizeChanged(object sender, SizeChangedEventArgs e)
        => PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1080);

    private async Task StartPairingSessionAsync()
    {
        ResetUi();
        await _pairingManager.StartPairingAsync();
    }

    private void StopPairingSession()
    {
        _countdownTimer?.Stop();
        _countdownTimer = null;
        _pairingManager.Stop();
    }

    private void ResetUi()
    {
        QrLoadingRing.Visibility = Visibility.Visible;
        QrContainer.Visibility = Visibility.Collapsed;
        SuccessOverlay.Visibility = Visibility.Collapsed;
        CardPairSuccess.Visibility = Visibility.Collapsed;
        PairInfoBar.IsOpen = false;
        PulseRing.IsActive = true;
        TxtPulsingStatus.Text = "正在启动本地配对服务…";
        _secondsLeft = 180;
        TxtCountdown.Text = "二维码有效期 180 秒";
    }

    private async void OnPairingUriReceived(string uri)
    {
        DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                ImgQrCode.Source = await QrCodeHelper.GenerateQrBitmapAsync(uri, 8);
                QrLoadingRing.Visibility = Visibility.Collapsed;
                QrContainer.Visibility = Visibility.Visible;
                TxtPulsingStatus.Text = "等待 Android 扫码连接…";
                StartCountdown();
            }
            catch (Exception ex)
            {
                ShowInfo("二维码渲染失败", ex.Message, InfoBarSeverity.Error);
            }
        });
        await Task.CompletedTask;
    }

    private void StartCountdown()
    {
        _countdownTimer?.Stop();
        _secondsLeft = 180;
        _countdownTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _countdownTimer.Tick += (_, _) =>
        {
            _secondsLeft--;
            TxtCountdown.Text = _secondsLeft > 0 ? $"二维码有效期 {_secondsLeft} 秒" : "二维码已过期";
            if (_secondsLeft > 0) return;
            _countdownTimer.Stop();
            PulseRing.IsActive = false;
            TxtPulsingStatus.Text = "配对码已过期，请刷新";
        };
        _countdownTimer.Start();
    }

    private void OnPairingSucceeded(string deviceName)
    {
        DispatcherQueue.TryEnqueue(async () =>
        {
            _countdownTimer?.Stop();
            TxtPulsingStatus.Text = "正在启用锁屏蓝牙解锁…";
            try
            {
                var saved = MainWindow.Current.Vault.SaveUnlockCredentials(_account);
                if (!saved.Success) throw new InvalidOperationException(saved.ErrorMessage);
                await UnlockConfigurationManager.RunAsync("promote-pair");
                SuccessOverlay.Visibility = Visibility.Visible;
                CardPairSuccess.Visibility = Visibility.Visible;
                PulseRing.IsActive = false;
                TxtPulsingStatus.Text = "设备绑定成功";
                TxtSuccessDeviceName.Text = $"已绑定设备：{deviceName}";
                ShowInfo("绑定完成", "手机公钥与 Windows 凭据已保存到锁屏组件。", InfoBarSeverity.Success);
            }
            catch (Exception ex)
            {
                PulseRing.IsActive = false;
                TxtPulsingStatus.Text = "尚未在锁屏启用";
                ShowInfo("配对未完成", ex.Message, InfoBarSeverity.Error);
            }
        });
    }

    private void OnPairingFailed(string error)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            _countdownTimer?.Stop();
            PulseRing.IsActive = false;
            TxtPulsingStatus.Text = "配对失败";
            ShowInfo("无法完成配对", error, InfoBarSeverity.Error);
        });
    }

    private async void OnRefreshClicked(object sender, RoutedEventArgs e) => await StartPairingSessionAsync();

    private void ShowInfo(string title, string message, InfoBarSeverity severity)
    {
        PairInfoBar.Title = title;
        PairInfoBar.Message = message;
        PairInfoBar.Severity = severity;
        PairInfoBar.IsOpen = true;
    }

    private void OnBackClicked(object sender, RoutedEventArgs e)
    {
        StopPairingSession();
        MainWindow.Current.NavigateToHome();
    }
}
