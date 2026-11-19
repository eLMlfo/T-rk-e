// SPDX-License-Identifier: AGPL-3.0-only

using System.Text.Json;
using CouchSubs.Shell;

namespace CouchSubs.Shell.Tests;

public class SubtitleBackgroundSettingsTests
{
    [Fact]
    public void OldSettingsDefaultToOffAndEnabledSettingSurvivesSave()
    {
        Assert.False(JsonSerializer.Deserialize<UserSettings>("{}")!.WatchSubtitleBackground);
        var saved = JsonSerializer.Serialize(new UserSettings { WatchSubtitleBackground = true });
        Assert.True(JsonSerializer.Deserialize<UserSettings>(saved)!.WatchSubtitleBackground);
    }
}
