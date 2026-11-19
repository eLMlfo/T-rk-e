// SPDX-License-Identifier: AGPL-3.0-only

using CouchSubs.Core;

namespace CouchSubs.Core.Tests;

public class SubtitleBackgroundTests
{
    private const int Width = 128, Height = 48;

    [Fact]
    public void KeepsSubtitleOnBluePanelInDarkSceneAndDropsOtherText()
    {
        var frame = Solid(20, 20, 20);
        Fill(frame, 24, 16, 104, 32, 33, 33, 46);
        Fill(frame, 40, 21, 88, 27, 230, 230, 230); // Yazı pikselleri zemini örtüyor.
        var reading = new Reading([
            new TextLine("Hey, you've reached Brian Heder.", new TextBox(0.32, 20.0 / Height, 0.68, 27.0 / Height)),
            new TextLine("PHOTO MODE", new TextBox(0.80, 0.30, 0.95, 0.36)),
        ]);

        Assert.Equal("Hey, you've reached Brian Heder.", SubtitleBackground.Filter(reading, frame).Text);
    }

    [Fact]
    public void RejectsNeutralDarkPanel()
    {
        var frame = Solid(20, 20, 20);
        Fill(frame, 24, 16, 104, 32, 33, 33, 33);
        var reading = new Reading([new TextLine("Brian", new TextBox(0.32, 20.0 / Height, 0.68, 27.0 / Height))]);
        Assert.Empty(SubtitleBackground.Filter(reading, frame).Lines);
    }

    [Fact]
    public void AcceptsMeasuredColorAndCompressionVariation()
    {
        Assert.True(SubtitleBackground.IsPanelColor(33, 33, 46));
        Assert.True(SubtitleBackground.IsPanelColor(22, 22, 34));
        Assert.False(SubtitleBackground.IsPanelColor(33, 33, 33));
    }

    private static Frame Solid(byte red, byte green, byte blue)
    {
        var frame = Frame.Blank(Width, Height);
        Fill(frame, 0, 0, Width, Height, red, green, blue);
        return frame;
    }

    private static void Fill(Frame frame, int left, int top, int right, int bottom, byte red, byte green, byte blue)
    {
        for (var y = top; y < bottom; y++)
            for (var x = left; x < right; x++)
            {
                var i = y * frame.Stride + x * 4;
                frame.Pixels[i] = blue;
                frame.Pixels[i + 1] = green;
                frame.Pixels[i + 2] = red;
                frame.Pixels[i + 3] = 255;
            }
    }
}
