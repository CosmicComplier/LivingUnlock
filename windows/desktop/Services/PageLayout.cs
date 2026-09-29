using System;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LivingUnlock.Windows.Services;

public static class PageLayout
{
    public static void FitContent(ScrollViewer scroll, FrameworkElement host,
        FrameworkElement content, double maximumWidth, double sideGutter = 34)
    {
        double viewport = scroll.ViewportWidth > 0 ? scroll.ViewportWidth : scroll.ActualWidth;
        if (viewport <= 0) return;
        host.Width = viewport;
        content.Width = Math.Max(0, Math.Min(maximumWidth, viewport - sideGutter * 2));
    }
}
