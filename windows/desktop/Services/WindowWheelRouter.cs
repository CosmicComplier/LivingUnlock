using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using Microsoft.UI.Xaml.Controls;

namespace LivingUnlock.Windows.Services;

// Route wheel messages from the app window to the active page's ScrollViewer.
// WinUI child controls can consume the pointer event before the page sees it.
public sealed class WindowWheelRouter : IDisposable
{
    private const uint WmGetMinMaxInfo = 0x0024;
    private const uint WmMouseWheel = 0x020A;
    private const uint WmPointerWheel = 0x024E;
    private readonly IntPtr _window;
    private readonly Func<ScrollViewer?> _activeScroll;
    private readonly SubclassProc _callback;
    private readonly HashSet<IntPtr> _targets = new();
    private int _minWidth;
    private int _minHeight;

    [StructLayout(LayoutKind.Sequential)]
    private struct POINT
    {
        public int x;
        public int y;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct MINMAXINFO
    {
        public POINT ptReserved;
        public POINT ptMaxSize;
        public POINT ptMaxPosition;
        public POINT ptMinTrackSize;
        public POINT ptMaxTrackSize;
    }

    private delegate IntPtr SubclassProc(IntPtr hwnd, uint message, IntPtr wParam,
        IntPtr lParam, UIntPtr subclassId, UIntPtr referenceData);
    private delegate bool EnumChildProc(IntPtr hwnd, IntPtr data);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetWindowSubclass(IntPtr hwnd, SubclassProc callback,
        UIntPtr subclassId, UIntPtr referenceData);

    [DllImport("comctl32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool RemoveWindowSubclass(IntPtr hwnd, SubclassProc callback,
        UIntPtr subclassId);

    [DllImport("comctl32.dll")]
    private static extern IntPtr DefSubclassProc(IntPtr hwnd, uint message,
        IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool EnumChildWindows(IntPtr parent, EnumChildProc callback, IntPtr data);

    public WindowWheelRouter(IntPtr window, Func<ScrollViewer?> activeScroll)
    {
        _window = window;
        _activeScroll = activeScroll;
        _callback = HandleMessage;
        Attach(window);
        AttachChildren();
    }

    public void SetMinSize(int minWidth, int minHeight)
    {
        _minWidth = minWidth;
        _minHeight = minHeight;
    }

    private void Attach(IntPtr hwnd)
    {
        if (!_targets.Contains(hwnd) && SetWindowSubclass(hwnd, _callback, UIntPtr.Zero, UIntPtr.Zero))
            _targets.Add(hwnd);
    }

    public void AttachChildren()
    {
        EnumChildProc callback = (hwnd, _) => { Attach(hwnd); return true; };
        EnumChildWindows(_window, callback, IntPtr.Zero);
        GC.KeepAlive(callback);
    }

    private IntPtr HandleMessage(IntPtr hwnd, uint message, IntPtr wParam,
        IntPtr lParam, UIntPtr subclassId, UIntPtr referenceData)
    {
        if (message == WmGetMinMaxInfo && hwnd == _window && _minWidth > 0 && _minHeight > 0)
        {
            DefSubclassProc(hwnd, message, wParam, lParam);
            try
            {
                var mmi = Marshal.PtrToStructure<MINMAXINFO>(lParam);
                mmi.ptMinTrackSize.x = Math.Max(mmi.ptMinTrackSize.x, _minWidth);
                mmi.ptMinTrackSize.y = Math.Max(mmi.ptMinTrackSize.y, _minHeight);
                Marshal.StructureToPtr(mmi, lParam, false);
            }
            catch { }
            return IntPtr.Zero;
        }

        if (message == WmMouseWheel || message == WmPointerWheel)
        {
            try
            {
                var scroll = _activeScroll();
                if (scroll is not null && scroll.ScrollableHeight > 0)
                {
                    int delta = unchecked((short)((wParam.ToInt64() >> 16) & 0xffff));
                    if (delta != 0)
                    {
                        double target = Math.Clamp(scroll.VerticalOffset - delta * 72.0 / 120.0,
                            0, scroll.ScrollableHeight);
                        scroll.ChangeView(null, target, null, false);
                        return IntPtr.Zero;
                    }
                }
            }
            catch { /* Leave the native wheel path available if a page is changing. */ }
        }
        return DefSubclassProc(hwnd, message, wParam, lParam);
    }

    public void Dispose()
    {
        foreach (var target in _targets)
            RemoveWindowSubclass(target, _callback, UIntPtr.Zero);
        _targets.Clear();
        GC.KeepAlive(_callback);
    }
}
