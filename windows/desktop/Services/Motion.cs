using System;
using System.Numerics;
using Microsoft.UI.Composition;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Hosting;
using Windows.UI.ViewManagement;

namespace LivingUnlock.Windows.Services;

public static class Motion
{
    private static readonly UISettings Settings = new();

    public static void Enter(UIElement element, int delayMs = 0, float offsetY = 18f)
    {
        if (!Settings.AnimationsEnabled)
        {
            element.Opacity = 1;
            return;
        }

        ElementCompositionPreview.SetIsTranslationEnabled(element, true);
        var visual = ElementCompositionPreview.GetElementVisual(element);
        var compositor = visual.Compositor;
        var easing = compositor.CreateCubicBezierEasingFunction(
            new Vector2(0.23f, 1f), new Vector2(0.32f, 1f));

        visual.Opacity = 0f;
        visual.Properties.InsertVector3("Translation", new Vector3(0, offsetY, 0));

        var opacity = compositor.CreateScalarKeyFrameAnimation();
        opacity.InsertKeyFrame(1f, 1f, easing);
        opacity.Duration = TimeSpan.FromMilliseconds(230);
        opacity.DelayTime = TimeSpan.FromMilliseconds(delayMs);

        var translation = compositor.CreateVector3KeyFrameAnimation();
        translation.InsertKeyFrame(1f, Vector3.Zero, easing);
        translation.Duration = TimeSpan.FromMilliseconds(260);
        translation.DelayTime = TimeSpan.FromMilliseconds(delayMs);

        visual.StartAnimation("Opacity", opacity);
        visual.StartAnimation("Translation", translation);
    }

    public static void SetHover(UIElement element, bool hovered)
    {
        if (!Settings.AnimationsEnabled) return;

        var visual = ElementCompositionPreview.GetElementVisual(element);
        visual.CenterPoint = new Vector3(element.ActualSize.X / 2f, element.ActualSize.Y / 2f, 0);
        var spring = visual.Compositor.CreateSpringVector3Animation();
        spring.FinalValue = hovered ? new Vector3(1.012f, 1.012f, 1f) : Vector3.One;
        spring.DampingRatio = 0.9f;
        spring.Period = TimeSpan.FromMilliseconds(120);
        visual.StartAnimation("Scale", spring);
    }

    public static void Press(UIElement element)
    {
        if (!Settings.AnimationsEnabled) return;
        var visual = ElementCompositionPreview.GetElementVisual(element);
        visual.CenterPoint = new Vector3(element.ActualSize.X / 2f, element.ActualSize.Y / 2f, 0);
        var compositor = visual.Compositor;
        var animation = compositor.CreateVector3KeyFrameAnimation();
        animation.InsertKeyFrame(0.45f, new Vector3(0.982f, 0.982f, 1f));
        animation.InsertKeyFrame(1f, Vector3.One);
        animation.Duration = TimeSpan.FromMilliseconds(140);
        visual.StartAnimation("Scale", animation);
    }
}
