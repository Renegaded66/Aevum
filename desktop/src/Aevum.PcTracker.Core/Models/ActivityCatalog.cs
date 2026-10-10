namespace Aevum.PcTracker.Core.Models;

/// <summary>
/// Eine Aktivität des PC-Katalogs („fertige Auswahl" für die Programm-Zuordnung).
/// Die Keys sind stabil und wandern als <c>activityKey</c> über die API nach Aevum —
/// Aevum braucht dadurch kein Vorwissen über den Katalog und ordnet nur über
/// Key/Name zu (siehe PC-TRACKER-PROTOCOL.md, Abschnitt 5).
/// </summary>
public sealed record PcActivity(
    string Key,
    string Name,
    string CategoryKey,
    string Icon);

/// <summary>Der feste Aktivitätskatalog der Windows-App.</summary>
public static class ActivityCatalog
{
    public static readonly IReadOnlyList<PcActivity> All = new List<PcActivity>
    {
        new("gaming",        "Zocken",        "digital",       "🎮"),
        new("work",          "Arbeit",        "work",          "💼"),
        new("programming",   "Programmieren", "work",          "💻"),
        new("video",         "Video schauen", "digital",       "📺"),
        new("music",         "Musik hören",   "leisure",       "🎧"),
        new("browsing",      "Surfen",        "digital",       "🌐"),
        new("learning",      "Lernen",        "learning",      "📚"),
        new("reading",       "Lesen",         "leisure",       "📖"),
        new("communication", "Kommunizieren", "relationships", "💬"),
        new("creating",      "Kreatives",     "leisure",       "🎨"),
        new("other",         "Sonstiges",     "unknown",       "⚙️"),
    };

    public static PcActivity? ByKey(string? key) =>
        key is null ? null : All.FirstOrDefault(a => a.Key == key);

    public static PcActivity ByKeyOrOther(string? key) => ByKey(key) ?? ByKey("other")!;
}

/// <summary>
/// Kuratierte Vorauswahl bekannter Programme (Devon: „Alles was geht mit
/// Vorauswahl, so wenig wie möglich zum selber eingeben"). Die Liste wird im
/// Regeln-Tab zuerst gezeigt; zusätzlich erscheinen die tatsächlich erkannten
/// Programme. Namen sind Vorschläge — der Programm-Key bleibt die .exe (lowercase).
/// </summary>
public static class KnownPrograms
{
    public sealed record Entry(string Exe, string Name, string SuggestedActivityKey);

    public static readonly IReadOnlyList<Entry> All = new List<Entry>
    {
        // Spiele
        new("minecraft.exe",        "Minecraft",        "gaming"),
        new("minecraftlauncher.exe","Minecraft Launcher","gaming"),
        new("steam.exe",            "Steam",            "gaming"),
        new("epicgameslauncher.exe","Epic Games",       "gaming"),
        new("leagueclient.exe",     "League of Legends","gaming"),
        new("riotclientservices.exe","Riot Client",     "gaming"),
        new("gta5.exe",             "GTA V",            "gaming"),
        new("cs2.exe",              "Counter-Strike 2", "gaming"),
        new("valorant.exe",         "VALORANT",         "gaming"),
        // Arbeit & Programmieren
        new("code.exe",             "Visual Studio Code","programming"),
        new("devenv.exe",           "Visual Studio",    "programming"),
        new("idea64.exe",           "IntelliJ IDEA",    "programming"),
        new("pycharm64.exe",        "PyCharm",          "programming"),
        new("gitbash.exe",          "Git Bash",         "programming"),
        new("windowsterminal.exe",  "Windows Terminal", "programming"),
        new("cmd.exe",              "Eingabeaufforderung","programming"),
        new("powershell.exe",       "PowerShell",       "programming"),
        new("winword.exe",          "Microsoft Word",   "work"),
        new("excel.exe",            "Microsoft Excel",  "work"),
        new("powerpnt.exe",         "PowerPoint",       "work"),
        new("outlook.exe",          "Outlook",          "communication"),
        new("teams.exe",            "Microsoft Teams (klassisch)", "communication"),
        new("ms-teams.exe",         "Microsoft Teams",  "communication"),
        // Kommunikation
        new("discord.exe",          "Discord",          "communication"),
        new("slack.exe",            "Slack",            "communication"),
        new("whatsapp.exe",         "WhatsApp",         "communication"),
        new("signal.exe",           "Signal",           "communication"),
        new("telegram.exe",         "Telegram",         "communication"),
        new("zoom.exe",             "Zoom",             "communication"),
        // Medien
        new("chrome.exe",           "Google Chrome",    "browsing"),
        new("firefox.exe",          "Firefox",          "browsing"),
        new("msedge.exe",           "Microsoft Edge",   "browsing"),
        new("opera.exe",            "Opera",            "browsing"),
        new("brave.exe",            "Brave",            "browsing"),
        new("spotify.exe",          "Spotify",          "music"),
        new("vlc.exe",              "VLC",              "video"),
        new("netflix.exe",          "Netflix",          "video"),
        new("obs64.exe",            "OBS Studio",       "creating"),
        new("gimp-2.10.exe",        "GIMP",             "creating"),
        new("krita.exe",            "Krita",            "creating"),
        new("audacity.exe",         "Audacity",         "creating"),
        new("notion.exe",           "Notion",           "work"),
        new("obsidian.exe",         "Obsidian",         "work"),
        new("kindle.exe",           "Kindle",           "reading"),
        new("acrord32.exe",         "Adobe Reader",     "reading"),
        new("sumatrapdf.exe",       "SumatraPDF",       "reading"),
    };
}
