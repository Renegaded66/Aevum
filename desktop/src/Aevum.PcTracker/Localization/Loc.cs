using Avalonia;
using Avalonia.Controls;

namespace Aevum.PcTracker.Localization;

/// <summary>
/// Zentrale UI-Texte (Deutsch/Englisch, Default Englisch). Die XAML-Seiten binden
/// über <c>{DynamicResource s.…}</c> — beim Sprachwechsel wird das komplette
/// Sprach-Wörterbuch in den App-Ressourcen ersetzt, wodurch Avalonia alle
/// Texte live aktualisiert. C#-Stellen (Tray-Menü) nutzen <see cref="S"/> und
/// hören auf <see cref="LanguageChanged"/>.
/// </summary>
public static class Loc
{
    public static string Current { get; private set; } = "en";

    public static event Action? LanguageChanged;

    private static ResourceDictionary? _languageDict;

    public static bool IsGerman => Current == "de";

    /// <summary>Setzt die Sprache ohne Ressourcen-Update (App-Start, vor dem Fensterbau).</summary>
    public static void Init(string? language)
    {
        Current = language == "de" ? "de" : "en";
    }

    /// <summary>Sprachwechsel zur Laufzeit: Ressourcen ersetzen + Welt informieren.</summary>
    public static void SetLanguage(string language)
    {
        var next = language == "de" ? "de" : "en";
        if (next == Current)
        {
            return;
        }
        Current = next;
        if (Application.Current is { } app)
        {
            Apply(app);
        }
        LanguageChanged?.Invoke();
    }

    /// <summary>Legt das Sprach-Wörterbuch in die App-Ressourcen (ersetzt das alte).</summary>
    public static void Apply(Application app)
    {
        var dict = new ResourceDictionary();
        foreach (var (key, value) in Table(Current))
        {
            dict[key] = value;
        }
        if (_languageDict is not null)
        {
            app.Resources.MergedDictionaries.Remove(_languageDict);
        }
        app.Resources.MergedDictionaries.Insert(0, dict);
        _languageDict = dict;
    }

    /// <summary>Text für Code-Stellen (Tray, Meldungen).</summary>
    public static string S(string key)
        => Table(Current).TryGetValue(key, out var value) ? value : key;

    /// <summary>Formatter-Variante: <c>F("rules_threshold", "1 minute")</c>.</summary>
    public static string F(string key, params object[] args)
        => string.Format(System.Globalization.CultureInfo.CurrentCulture, S(key), args);

    private static Dictionary<string, string> Table(string language)
        => language == "de" ? German : English;

    private static readonly Dictionary<string, string> English = new()
    {
        // Navigation + Rahmen
        ["s.nav_dashboard"] = "Dashboard",
        ["s.nav_rules"] = "Programs",
        ["s.nav_history"] = "History",
        ["s.nav_pairing"] = "Pairing",
        ["s.nav_settings"] = "Settings",
        ["s.status_starting"] = "Starting…",
        ["s.status_paused"] = "Recording paused",
        ["s.status_active"] = "Recording active",
        ["s.status_inactive"] = "Recording inactive (Windows only)",
        ["s.pause"] = "Pause",
        ["s.resume"] = "Resume",
        ["s.common_refresh"] = "Refresh",
        ["s.common_save"] = "Save",
        ["s.common_cancel"] = "Cancel",
        ["s.common_remove"] = "Remove",
        ["s.common_edit"] = "Edit",
        ["s.common_add"] = "Add",

        // Sync-Bar (auf jeder Seite)
        ["s.sync_last"] = "Last sync",
        ["s.sync_never"] = "Last sync: never",
        ["s.sync_just_now"] = "just now",
        ["s.sync_min_ago"] = "{0} min ago",
        ["s.sync_at"] = "at {0}",
        ["s.sync_connected"] = "Phone connected",
        ["s.sync_not_connected"] = "No phone connected yet",
        ["s.sync_hint"] = "Syncing runs in the Aevum app: Settings → PC-Tracker → Sync now.",

        // Dashboard
        ["s.dash_title"] = "Dashboard",
        ["s.dash_subtitle"] = "Your PC, at a glance.",
        ["s.dash_now"] = "RECORDING NOW",
        ["s.dash_now_idle"] = "NOTHING BEING RECORDED",
        ["s.dash_nothing"] = "Nothing is being recorded right now.",
        ["s.dash_nothing_hint"] = "Open a mapped program to start recording — or add a mapping under Programs.",
        ["s.dash_since"] = "running since {0}",
        ["s.dash_total_today"] = "recorded today",
        ["s.dash_recent"] = "RECENT SESSIONS",
        ["s.dash_recent_empty"] = "No sessions recorded yet.",
        ["s.dash_phone"] = "YOUR PHONE",
        ["s.dash_phone_connected"] = "Connected — {0}",
        ["s.dash_phone_none"] = "Not connected yet.",
        ["s.dash_phone_hint"] = "The Aevum app fetches recordings automatically every 15 minutes in the same Wi-Fi and mirrors your activity list back — set up pairing once.",
        ["s.dash_setup"] = "Set up pairing",
        ["s.dash_tracking_today"] = "Today",
        ["s.dash_sessions"] = "recordings",
        ["s.row_since"] = "since {0}",
        ["s.dash_waiting"] = "Waiting for activity…",
        ["s.dash_paused_now"] = "Recording is paused.",
        ["s.dash_windows_only"] = "PC recording is active on Windows only.",
        ["s.dash_open_unmapped"] = "{0} is open — not mapped to an activity yet.",
        ["s.dash_no_window"] = "No active window detected.",
        ["s.dash_ready"] = "Ready",
        ["s.dash_reachable"] = "Reachable at {0}:{1} (same Wi-Fi).",
        ["s.dash_server_error"] = "LAN server could not start: {0}",
        ["s.dash_server_starting"] = "LAN server is starting…",
        ["s.dash_phone_detail"] = "Last sync {0}",

        // Regeln
        ["s.rules_title"] = "Programs",
        ["s.rules_subtitle"] = "Map PC programs to your Aevum activities — only mapped programs are recorded.",
        ["s.rules_mine"] = "YOUR MAPPINGS",
        ["s.rules_none"] = "No mappings yet — pick a program below.",
        ["s.rules_ends_label"] = "Ends after",
        ["s.rules_add"] = "ADD A PROGRAM",
        ["s.rules_search"] = "Search… e.g. Minecraft, Chrome, Spotify",
        ["s.rules_running_btn"] = "Programs open right now",
        ["s.rules_running_title"] = "OPEN RIGHT NOW",
        ["s.rules_running_empty"] = "Nothing found — open the program, then refresh.",
        ["s.rules_running_map"] = "Map",
        ["s.rules_custom_label"] = "Not listed? Type the program name or .exe:",
        ["s.rules_custom_add"] = "Add",
        ["s.rules_edit_existing"] = "Editing existing mapping",
        ["s.rules_badge_seen"] = "Seen on this PC",
        ["s.rules_badge_suggestion"] = "Suggestion",
        ["s.rules_badge_mapped"] = "Mapped",
        ["s.rules_activity"] = "Activity",
        ["s.rules_activity_app"] = "From your phone",
        ["s.rules_activity_catalog"] = "General",
        ["s.rules_threshold"] = "Ends after {0} without activity",
        ["s.rules_mode"] = "Recording runs",
        ["s.rules_mode_fg"] = "Only while in the foreground",
        ["s.rules_mode_bg"] = "Also while running in the background",
        ["s.rules_privacy"] = "Recorded: only what you map here. Transferred: only at home in your Wi-Fi — when the Aevum app picks it up. Nothing goes to the internet.",
        ["s.rules_apply_changes"] = "Waiting for phone — activities update automatically after the next sync.",

        // Verlauf
        ["s.hist_title"] = "History",
        ["s.hist_subtitle"] = "All recordings on this PC (already synced ones stay in Aevum).",
        ["s.hist_range"] = "Period",
        ["s.hist_today"] = "Today",
        ["s.hist_7"] = "Last 7 days",
        ["s.hist_30"] = "Last 30 days",
        ["s.hist_365"] = "Last 365 days",
        ["s.hist_all"] = "All time",
        ["s.hist_empty"] = "No recordings in this period yet — map a program under Programs to start.",
        ["s.hist_open"] = "running",
        ["s.rec_1"] = "1 recording",
        ["s.rec_n"] = "{0} recordings",
        ["s.hist_summary"] = "{0} · {1} total",
        // PC 1.5.0: Aufzeichnung aus dem Verlauf entfernen (zweistufig)
        ["s.hist_delete"] = "Remove recording",
        ["s.hist_delete_q"] = "Remove?",
        ["s.hist_delete_yes"] = "Yes",
        ["s.hist_delete_no"] = "No",

        // Kopplung
        ["s.pair_title"] = "Pairing",
        ["s.pair_subtitle"] = "Connect the Aevum app on your phone — same Wi-Fi, no account, no cloud.",
        ["s.pair_step1"] = "1. Open the Aevum app → Settings → PC-Tracker.",
        ["s.pair_step2"] = "2. Tap \u201eFind PC in Wi-Fi\u201c (or enter the address below manually).",
        ["s.pair_step3"] = "3. Turn on pairing mode here and type the code into the app.",
        ["s.pair_mode_on"] = "PAIRING MODE IS ON",
        ["s.pair_mode_off"] = "PAIRING MODE IS OFF",
        ["s.pair_code_show"] = "Type this code into the Aevum app:",
        ["s.pair_waiting"] = "Waiting for your phone…",
        ["s.pair_start"] = "Turn on pairing mode",
        ["s.pair_stop"] = "Turn off pairing mode",
        ["s.pair_address"] = "Address for manual entry",
        ["s.pair_reset"] = "Reset access — every phone must pair again",
        ["s.pair_reset_confirm"] = "Reset access? Every paired phone will have to pair again with the new code.",
        ["s.pair_reset_done"] = "Access reset — new phones must pair again.",
        ["s.reset"] = "Reset",
        ["s.pair_how_title"] = "HOW TO CONNECT YOUR PHONE",
        ["s.pair_mode_label"] = "PAIRING MODE",
        ["s.pair_mode_desc"] = "While it is on, one phone can connect with the code.",
        ["s.pair_code_label"] = "YOUR CODE",
        ["s.pair_code_hint"] = "The code is valid until it is used — a new one appears automatically.",
        ["s.pair_new_code"] = "Generate a new code",
        ["s.pair_security"] = "SECURITY",
        ["s.pair_security_text"] = "After pairing, only your phone gets a key for this PC. Other devices on the network cannot fetch anything. You can turn pairing mode off anytime.",
        ["s.pair_firewall"] = "On first start Windows asks for permission for private networks — please allow it, otherwise Aevum cannot find this PC. Public networks stay untouched.",
        ["s.pair_no_lan"] = "Port {0} (no network address found — is this PC on Wi-Fi?)",
        ["s.pair_server_ready"] = "Ready for fetches from your home network",
        ["s.pair_no_fetch"] = "No phone has fetched data yet.",
        ["s.pair_last_fetch"] = "Last fetch {0}",

        // Einstellungen
        ["s.set_title"] = "Settings",
        ["s.set_subtitle"] = "Everything local — nothing leaves this PC unless the app fetches it in your Wi-Fi.",
        ["s.set_startup"] = "STARTUP",
        ["s.set_autostart"] = "Start with Windows",
        ["s.set_startmin"] = "Start minimized to the tray",
        ["s.set_language"] = "Language",
        ["s.set_tracking"] = "RECORDING",
        ["s.set_idle"] = "Pause detection — no input ends a recording",
        ["s.set_idle_after"] = "After",
        ["s.set_threshold"] = "Default for new mappings",
        ["s.set_retention"] = "Keep recordings",
        ["s.set_retention_note"] = "Older recordings are deleted automatically.",
        ["s.set_data"] = "DATA",
        ["s.set_open_folder"] = "Open data folder",
        ["s.set_delete_all"] = "Delete all recordings on this PC",
        ["s.set_delete_confirm"] = "Delete all recordings? Already synced sessions stay in Aevum.",
        ["s.set_about"] = "ABOUT",
        ["s.set_about_text"] = "Aevum PC-Tracker — part of Aevum. Free, without an account.",
        ["s.set_about_desc"] = "Transfers to Aevum happen only in your home Wi-Fi, directly from this PC to your phone. No account, no cloud, no cost.",
        ["s.set_protocol"] = "Protocol: docs/PC-TRACKER-PROTOCOL.md",
        ["s.set_idle_label"] = "Pause detection",
        ["s.set_idle_desc"] = "When you stop typing or moving the mouse this long, an active recording ends automatically.",
        ["s.set_idle_after_label"] = "Inactive after",
        ["s.set_threshold_label"] = "Default recording window",
        ["s.set_threshold_desc"] = "For new mappings: how long a recording keeps running after the program is closed.",
        ["s.set_retention_label"] = "Keep history",
        ["s.set_retention_desc"] = "Older recordings are removed automatically. Everything stays on this PC.",
        ["s.set_folder_label"] = "Data folder",
        ["s.set_open"] = "Open",
        ["s.set_delete_label"] = "Delete history",
        ["s.set_delete_btn"] = "Delete",
        ["s.set_deleted"] = "{0} deleted.",
        ["s.set_autostart_label"] = "Start with Windows",
        ["s.set_autostart_desc"] = "The app starts automatically and stays quietly in the system tray.",
        ["s.set_startmin_label"] = "Start minimized",
        ["s.set_startmin_desc"] = "Even when started manually, only appear in the system tray (click the icon to open).",
        ["s.idle_min"] = "{0} minutes",
        ["s.ret_days"] = "{0} days",
        ["s.ret_1y"] = "1 year",
        ["s.ret_2y"] = "2 years",
        ["s.days"] = "days",

        // Threshold-Labels
        ["s.thr_30"] = "30 seconds",
        ["s.thr_60"] = "1 minute",
        ["s.thr_120"] = "2 minutes",
        ["s.thr_180"] = "3 minutes",
        ["s.thr_300"] = "5 minutes",
        ["s.thr_600"] = "10 minutes",
        ["s.thr_900"] = "15 minutes",
        ["s.thr_1800"] = "30 minutes",

        // Tray
        ["s.tray_open"] = "Open Aevum PC-Tracker",
        ["s.tray_pause"] = "Pause recording",
        ["s.tray_resume"] = "Resume recording",
        ["s.tray_quit"] = "Quit",
        ["s.tray_tooltip"] = "Aevum PC-Tracker — recording",
        ["s.tray_tooltip_paused"] = "Aevum PC-Tracker — paused",

        // Meldungen (Start)
        ["s.msg_running_title"] = "Aevum PC-Tracker is already running",
        ["s.msg_running_body"] = "The app is already active in the system tray. You can open or quit it there.",
        ["s.msg_startfail_title"] = "Aevum PC-Tracker could not start",
        ["s.msg_startfail_body"] = "The local data store could not be opened:",
        ["s.language_tooltip"] = "Switch language (English / Deutsch)",

        // Allgemeine Aufzeichnung (Dashboard-Karte)
        ["s.gen_title"] = "GENERAL RECORDING",
        ["s.gen_desc"] = "Records the selected activity whenever you use the PC and no mapping applies.",
        ["s.gen_toggle"] = "Record general activity",
        ["s.gen_activity"] = "Activity",
        ["s.gen_choose"] = "Choose activity…",
        ["s.gen_empty"] = "Sync with your phone first — your activities appear here.",
        ["s.gen_stops"] = "Ends after 1 minute without input (mouse/keyboard or sound).",
        ["s.gen_in_use"] = "PC in use",

        // Aktivität starten (PC 1.3.0) — Dashboard-Karte
        ["s.start_title"] = "START ACTIVITY",
        ["s.start_desc"] = "Pick an activity from your Aevum list — start it right away or with a head start.",
        ["s.start_paused"] = "Recording is paused — resume tracking to start an activity.",
        // PC 1.5.0: Handy zeichnet auf → PC-Start wartet (Handy hat Vorrang)
        ["s.start_phone_priority"] = "Your phone is recording right now — PC start waits until it's done (your phone has priority).",
        ["s.start_now"] = "Start now",
        ["s.start_in_5"] = "+5 min",
        ["s.start_in_15"] = "+15 min",
        ["s.start_in_30"] = "+30 min",
        ["s.start_cancel"] = "Cancel",
        ["s.start_pending"] = "{0} starts in {1}",
        ["s.start_stop"] = "Stop",
        ["s.start_hint"] = "While a manually started activity is running, program tracking and general recording stay quiet — only this activity is recorded.",
        ["s.man_started"] = "Manually started",
        ["s.dash_phone_activities"] = "{0} activities from your phone",

        // Live vom Handy (PC 1.4.0) — Dashboard-Karte
        ["s.plive_title"] = "ON YOUR PHONE",
        ["s.plive_running"] = "Running since {0} · {1}",
        ["s.plive_paused"] = "Paused — recorded until {0}",
        ["s.plive_pause"] = "Pause",
        ["s.plive_resume"] = "Resume",
        ["s.plive_stop"] = "Stop",
        ["s.plive_hint"] = "Live from your phone — controls apply within seconds (same Wi-Fi).",
        ["s.plive_stale"] = "Last update {0} — control needs the same Wi-Fi.",
        // PC 1.5.2: Transparenz — wie frisch ist die letzte Handy-Meldung
        ["s.plive_last_seen"] = "Last ping {0}",
        ["s.plive_busy"] = "Command sent — waiting for your phone…",
        ["s.plive_badge"] = "● LIVE",

        // PC 1.5.0: Handy-Vorrang + deutlich sichtbare Aufnahme
        ["s.dash_rec_badge"] = "● RECORDING",
        ["s.dash_priority_label"] = "PHONE GOES FIRST",
        ["s.dash_phone_recording"] = "Your phone is recording",
        ["s.dash_phone_priority"] = "The PC holds back while your phone records — only one recording runs at a time, and your phone has priority.",

        // Updates (Einstellungen)
        ["s.upd_title"] = "UPDATES",
        ["s.upd_current"] = "Installed version {0}",
        ["s.upd_auto"] = "Checked automatically at every start.",
        ["s.upd_check"] = "Check for updates",
        ["s.upd_checking"] = "Checking…",
        ["s.upd_uptodate"] = "You are up to date.",
        ["s.upd_available"] = "Version {0} is available.",
        ["s.upd_notes"] = "What's new:",
        ["s.upd_download"] = "Download & restart",
        ["s.upd_downloading"] = "Downloading… {0} %",
        ["s.upd_installing"] = "Installing — the app restarts by itself…",
        ["s.upd_manual"] = "Automatic update is not possible here — download manually:",
        ["s.upd_error"] = "Could not check for updates (offline?).",
        ["s.upd_badge"] = "Update available",
        ["s.upd_open"] = "Open release page",
        ["s.upd_keep"] = "Your settings and recordings stay in place — the update only replaces the program files.",
        // PC 1.5.1: ehrliches Fehlerband + Nachkontrolle
        ["s.upd_failed_title"] = "The last update did not go through",
        ["s.upd_failed_body"] = "The target was v{0}, but v{1} is still running — the program files were not replaced. The details are in the update log.",
        ["s.upd_open_folder"] = "Open log folder",
        ["s.upd_dismiss"] = "Got it",
    };

    private static readonly Dictionary<string, string> German = new()
    {
        ["s.nav_dashboard"] = "Dashboard",
        ["s.nav_rules"] = "Programme",
        ["s.nav_history"] = "Verlauf",
        ["s.nav_pairing"] = "Kopplung",
        ["s.nav_settings"] = "Einstellungen",
        ["s.status_starting"] = "Wird gestartet…",
        ["s.status_paused"] = "Aufzeichnung pausiert",
        ["s.status_active"] = "Aufzeichnung aktiv",
        ["s.status_inactive"] = "Aufzeichnung inaktiv (nur unter Windows)",
        ["s.pause"] = "Pausieren",
        ["s.resume"] = "Fortsetzen",
        ["s.common_refresh"] = "Aktualisieren",
        ["s.common_save"] = "Speichern",
        ["s.common_cancel"] = "Abbrechen",
        ["s.common_remove"] = "Entfernen",
        ["s.common_edit"] = "Bearbeiten",
        ["s.common_add"] = "Hinzufügen",

        ["s.sync_last"] = "Letzter Sync",
        ["s.sync_never"] = "Letzter Sync: noch nie",
        ["s.sync_just_now"] = "gerade eben",
        ["s.sync_min_ago"] = "vor {0} Min",
        ["s.sync_at"] = "um {0}",
        ["s.sync_connected"] = "Handy verbunden",
        ["s.sync_not_connected"] = "Noch kein Handy verbunden",
        ["s.sync_hint"] = "Der Abgleich läuft in der Aevum-App: Einstellungen → PC-Tracker → Jetzt synchronisieren.",

        ["s.dash_title"] = "Dashboard",
        ["s.dash_subtitle"] = "Dein PC auf einen Blick.",
        ["s.dash_now"] = "JETZT AUFZEICHNUNG",
        ["s.dash_now_idle"] = "GERADE KEINE AUFZEICHNUNG",
        ["s.dash_nothing"] = "Gerade wird nichts aufgezeichnet.",
        ["s.dash_nothing_hint"] = "Öffne ein zugeordnetes Programm — oder lege unter Programme eine Zuordnung an.",
        ["s.dash_since"] = "läuft seit {0}",
        ["s.dash_total_today"] = "heute aufgezeichnet",
        ["s.dash_recent"] = "LETZTE AUFZEICHNUNGEN",
        ["s.dash_recent_empty"] = "Noch keine Aufzeichnungen.",
        ["s.dash_phone"] = "DEIN HANDY",
        ["s.dash_phone_connected"] = "Verbunden — {0}",
        ["s.dash_phone_none"] = "Noch nicht verbunden.",
        ["s.dash_phone_hint"] = "Die Aevum-App holt die Aufzeichnungen im gleichen WLAN automatisch alle 15 Minuten ab und spiegelt deine Aktivitätsliste herüber — die Kopplung richtest du einmal ein.",
        ["s.dash_setup"] = "Kopplung einrichten",
        ["s.dash_tracking_today"] = "Heute",
        ["s.dash_sessions"] = "Aufzeichnungen",
        ["s.row_since"] = "seit {0}",
        ["s.dash_waiting"] = "Warte auf Aktivität…",
        ["s.dash_paused_now"] = "Die Aufzeichnung ist angehalten.",
        ["s.dash_windows_only"] = "PC-Aufzeichnung ist nur unter Windows aktiv.",
        ["s.dash_open_unmapped"] = "{0} ist offen — noch keiner Aktivität zugeordnet.",
        ["s.dash_no_window"] = "Kein aktives Fenster erkannt.",
        ["s.dash_ready"] = "Bereit",
        ["s.dash_reachable"] = "Erreichbar unter {0}:{1} (gleiches WLAN).",
        ["s.dash_server_error"] = "LAN-Server konnte nicht starten: {0}",
        ["s.dash_server_starting"] = "LAN-Server startet…",
        ["s.dash_phone_detail"] = "Zuletzt synchronisiert {0}",

        ["s.rules_title"] = "Programme",
        ["s.rules_subtitle"] = "Ordne PC-Programme den Aktivitäten aus Aevum zu — nur Zugeordnetes wird aufgezeichnet.",
        ["s.rules_mine"] = "DEINE ZUORDNUNGEN",
        ["s.rules_none"] = "Noch keine Zuordnungen — wähle unten ein Programm aus.",
        ["s.rules_ends_label"] = "Endet nach",
        ["s.rules_add"] = "PROGRAMM HINZUFÜGEN",
        ["s.rules_search"] = "Suchen… z. B. Minecraft, Chrome, Spotify",
        ["s.rules_running_btn"] = "Jetzt geöffnete Programme",
        ["s.rules_running_title"] = "JETZT OFFEN",
        ["s.rules_running_empty"] = "Nichts gefunden — öffne das Programm und aktualisiere.",
        ["s.rules_running_map"] = "Zuordnen",
        ["s.rules_custom_label"] = "Nicht dabei? Programmname oder .exe eingeben:",
        ["s.rules_custom_add"] = "Hinzufügen",
        ["s.rules_edit_existing"] = "Bestehende Zuordnung bearbeiten",
        ["s.rules_badge_seen"] = "Auf diesem PC gesehen",
        ["s.rules_badge_suggestion"] = "Vorschlag",
        ["s.rules_badge_mapped"] = "Zugeordnet",
        ["s.rules_activity"] = "Aktivität",
        ["s.rules_activity_app"] = "Von deinem Handy",
        ["s.rules_activity_catalog"] = "Allgemein",
        ["s.rules_threshold"] = "Endet nach {0} ohne Sicht",
        ["s.rules_mode"] = "Aufnahme läuft",
        ["s.rules_mode_fg"] = "Nur im Vordergrund",
        ["s.rules_mode_bg"] = "Auch wenn es im Hintergrund läuft",
        ["s.rules_privacy"] = "Aufgezeichnet wird nur, was du hier zuordnest. Übertragen wird nur zuhause im WLAN — wenn die Aevum-App die Daten abholt. Nichts wandert ins Internet.",
        ["s.rules_apply_changes"] = "Warte auf Handy — Aktivitäten aktualisieren sich automatisch beim nächsten Sync.",

        ["s.hist_title"] = "Verlauf",
        ["s.hist_subtitle"] = "Alle Aufzeichnungen auf diesem PC (bereits synchronisierte bleiben in Aevum).",
        ["s.hist_range"] = "Zeitraum",
        ["s.hist_today"] = "Heute",
        ["s.hist_7"] = "Letzte 7 Tage",
        ["s.hist_30"] = "Letzte 30 Tage",
        ["s.hist_365"] = "Letzte 365 Tage",
        ["s.hist_all"] = "Gesamt",
        ["s.hist_empty"] = "Noch keine Aufzeichnungen in diesem Zeitraum — ordne unter „Programme“ ein Programm zu.",
        ["s.hist_open"] = "läuft",
        ["s.rec_1"] = "1 Aufzeichnung",
        ["s.rec_n"] = "{0} Aufzeichnungen",
        ["s.hist_summary"] = "{0} · {1} gesamt",
        // PC 1.5.0: Aufzeichnung aus dem Verlauf entfernen (zweistufig)
        ["s.hist_delete"] = "Aufzeichnung entfernen",
        ["s.hist_delete_q"] = "Entfernen?",
        ["s.hist_delete_yes"] = "Ja",
        ["s.hist_delete_no"] = "Nein",

        ["s.pair_title"] = "Kopplung",
        ["s.pair_subtitle"] = "Verbinde die Aevum-App auf deinem Handy — gleiches WLAN, kein Konto, keine Cloud.",
        ["s.pair_step1"] = "1. In Aevum: Einstellungen → PC-Tracker öffnen.",
        ["s.pair_step2"] = "2. Dort „PC im WLAN suchen“ — oder die Adresse unten manuell eintippen.",
        ["s.pair_step3"] = "3. Hier den Kopplungsmodus einschalten und den Code in der App eingeben.",
        ["s.pair_mode_on"] = "KOPPLUNGSMODUS IST AN",
        ["s.pair_mode_off"] = "KOPPLUNGSMODUS IST AUS",
        ["s.pair_code_show"] = "Gib diesen Code in der Aevum-App ein:",
        ["s.pair_waiting"] = "Warte auf dein Handy…",
        ["s.pair_start"] = "Kopplungsmodus starten",
        ["s.pair_stop"] = "Kopplungsmodus beenden",
        ["s.pair_address"] = "Adresse für manuelle Eingabe",
        ["s.pair_reset"] = "Zugang zurücksetzen — jedes Handy muss neu koppeln",
        ["s.pair_reset_confirm"] = "Zugang zurücksetzen? Jedes gekoppelte Handy muss mit dem neuen Code neu koppeln.",
        ["s.pair_reset_done"] = "Zugang zurückgesetzt — neue Kopplung nötig.",
        ["s.reset"] = "Zurücksetzen",
        ["s.pair_how_title"] = "SO VERBINDEST DU DEIN HANDY",
        ["s.pair_mode_label"] = "KOPPLUNGSMODUS",
        ["s.pair_mode_desc"] = "Solange er an ist, kann sich ein Handy mit dem Code verbinden.",
        ["s.pair_code_label"] = "DEIN CODE",
        ["s.pair_code_hint"] = "Der Code gilt, bis er benutzt wurde — danach kommt automatisch ein neuer.",
        ["s.pair_new_code"] = "Neuen Code erzeugen",
        ["s.pair_security"] = "SICHERHEIT",
        ["s.pair_security_text"] = "Nach der Kopplung bekommt nur dein Handy einen Schlüssel für diesen PC. Alle anderen Geräte im Netzwerk können nichts abrufen. Der Kopplungsmodus lässt sich jederzeit ausschalten.",
        ["s.pair_firewall"] = "Beim ersten Start fragt Windows nach der Freigabe für private Netzwerke — bitte zulassen, sonst kann Aevum den PC nicht finden. Öffentliche Netzwerke bleiben unberührt.",
        ["s.pair_no_lan"] = "Port {0} (keine Netzwerkadresse gefunden — ist der PC im WLAN?)",
        ["s.pair_server_ready"] = "Bereit für Abrufe aus dem Heimnetz",
        ["s.pair_no_fetch"] = "Bisher hat noch kein Handy Daten abgeholt.",
        ["s.pair_last_fetch"] = "Zuletzt abgeholt: {0}",

        ["s.set_title"] = "Einstellungen",
        ["s.set_subtitle"] = "Alles bleibt lokal — nichts verlässt diesen PC, außer die App holt es in deinem WLAN ab.",
        ["s.set_startup"] = "START",
        ["s.set_autostart"] = "Mit Windows starten",
        ["s.set_startmin"] = "Minimiert in den Infobereich starten",
        ["s.set_language"] = "Sprache",
        ["s.set_tracking"] = "AUFZEICHNUNG",
        ["s.set_idle"] = "Pause-Erkennung — keine Eingabe beendet die Aufnahme",
        ["s.set_idle_after"] = "Nach",
        ["s.set_threshold"] = "Standard für neue Zuordnungen",
        ["s.set_retention"] = "Aufzeichnungen behalten",
        ["s.set_retention_note"] = "Ältere Aufzeichnungen werden automatisch gelöscht.",
        ["s.set_data"] = "DATEN",
        ["s.set_open_folder"] = "Datenordner öffnen",
        ["s.set_delete_all"] = "Alle Aufzeichnungen auf diesem PC löschen",
        ["s.set_delete_confirm"] = "Alle Aufzeichnungen löschen? Bereits synchronisierte Sessions bleiben in Aevum.",
        ["s.set_about"] = "ÜBER",
        ["s.set_about_text"] = "Aevum PC-Tracker — Teil von Aevum. Kostenlos, ohne Konto.",
        ["s.set_about_desc"] = "Die Übertragung an Aevum läuft ausschließlich im Heimnetz, direkt von diesem PC zum Handy. Kein Konto, keine Cloud, keine Kosten.",
        ["s.set_protocol"] = "Protokoll: docs/PC-TRACKER-PROTOCOL.md",
        ["s.set_idle_label"] = "Pausen-Erkennung",
        ["s.set_idle_desc"] = "Wenn du so lange keine Eingabe machst, endet die laufende Aufzeichnung automatisch.",
        ["s.set_idle_after_label"] = "Inaktiv nach",
        ["s.set_threshold_label"] = "Standard-Aufnahmefenster",
        ["s.set_threshold_desc"] = "Für neue Zuordnungen: So lange läuft die Aufzeichnung nach, wenn das Programm geschlossen wird.",
        ["s.set_retention_label"] = "Verlauf aufbewahren",
        ["s.set_retention_desc"] = "Ältere Aufzeichnungen werden automatisch entfernt. Alles liegt nur auf diesem PC.",
        ["s.set_folder_label"] = "Datenordner",
        ["s.set_open"] = "Öffnen",
        ["s.set_delete_label"] = "Verlauf löschen",
        ["s.set_delete_btn"] = "Löschen",
        ["s.set_deleted"] = "{0} gelöscht.",
        ["s.set_autostart_label"] = "Mit Windows starten",
        ["s.set_autostart_desc"] = "Die App startet automatisch und bleibt still im Infobereich der Taskleiste.",
        ["s.set_startmin_label"] = "Beim Start minimiert",
        ["s.set_startmin_desc"] = "Auch beim manuellen Start nur im Infobereich erscheinen (per Klick auf das Symbol öffnen).",
        ["s.idle_min"] = "{0} Minuten",
        ["s.ret_days"] = "{0} Tage",
        ["s.ret_1y"] = "1 Jahr",
        ["s.ret_2y"] = "2 Jahre",
        ["s.days"] = "Tage",

        ["s.thr_30"] = "30 Sekunden",
        ["s.thr_60"] = "1 Minute",
        ["s.thr_120"] = "2 Minuten",
        ["s.thr_180"] = "3 Minuten",
        ["s.thr_300"] = "5 Minuten",
        ["s.thr_600"] = "10 Minuten",
        ["s.thr_900"] = "15 Minuten",
        ["s.thr_1800"] = "30 Minuten",

        ["s.tray_open"] = "Aevum PC-Tracker öffnen",
        ["s.tray_pause"] = "Aufzeichnung pausieren",
        ["s.tray_resume"] = "Aufzeichnung fortsetzen",
        ["s.tray_quit"] = "Beenden",
        ["s.tray_tooltip"] = "Aevum PC-Tracker — zeichnet auf",
        ["s.tray_tooltip_paused"] = "Aevum PC-Tracker — pausiert",

        ["s.msg_running_title"] = "Aevum PC-Tracker läuft bereits",
        ["s.msg_running_body"] = "Die App ist schon im Infobereich der Taskleiste aktiv. Dort lässt sie sich öffnen oder beenden.",
        ["s.msg_startfail_title"] = "Aevum PC-Tracker konnte nicht starten",
        ["s.msg_startfail_body"] = "Der lokale Datenspeicher ließ sich nicht öffnen:",
        ["s.language_tooltip"] = "Sprache wechseln (Deutsch / English)",

        ["s.gen_title"] = "ALLGEMEINE AUFZEICHNUNG",
        ["s.gen_desc"] = "Zeichnet die gewählte Aktivität auf, solange du den PC benutzt und keine Zuordnung greift.",
        ["s.gen_toggle"] = "Allgemeine Aktivität aufzeichnen",
        ["s.gen_activity"] = "Aktivität",
        ["s.gen_choose"] = "Aktivität wählen…",
        ["s.gen_empty"] = "Synchronisiere zuerst mit deinem Handy — dann erscheinen hier deine Aktivitäten.",
        ["s.gen_stops"] = "Endet nach 1 Minute ohne Eingabe (Maus/Tastatur oder Ton).",
        ["s.gen_in_use"] = "PC wird benutzt",

        // Aktivität starten (PC 1.3.0) — Dashboard-Karte
        ["s.start_title"] = "AKTIVITÄT STARTEN",
        ["s.start_desc"] = "Wähle eine Aktivität aus deiner Aevum-Liste — starte sie sofort oder mit Vorlaufzeit.",
        ["s.start_paused"] = "Aufzeichnung ist pausiert — zum Starten erst fortsetzen.",
        // PC 1.5.0: Handy zeichnet auf → PC-Start wartet (Handy hat Vorrang)
        ["s.start_phone_priority"] = "Dein Handy zeichnet gerade auf — der PC-Start wartet, bis es fertig ist (dein Handy hat Vorrang).",
        ["s.start_now"] = "Jetzt starten",
        ["s.start_in_5"] = "+5 Min",
        ["s.start_in_15"] = "+15 Min",
        ["s.start_in_30"] = "+30 Min",
        ["s.start_cancel"] = "Abbrechen",
        ["s.start_pending"] = "{0} startet in {1}",
        ["s.start_stop"] = "Stoppen",
        ["s.start_hint"] = "Solange eine manuell gestartete Aktivität läuft, ruhen Programm-Aufzeichnung und allgemeine Aufzeichnung — es wird nur diese Aktivität erfasst.",
        ["s.man_started"] = "Manuell gestartet",
        ["s.dash_phone_activities"] = "{0} Aktivitäten vom Handy",

        // Live vom Handy (PC 1.4.0) — Dashboard-Karte
        ["s.plive_title"] = "AUF DEINEM HANDY",
        ["s.plive_running"] = "Läuft seit {0} · {1}",
        ["s.plive_paused"] = "Pausiert — aufgezeichnet bis {0}",
        ["s.plive_pause"] = "Pausieren",
        ["s.plive_resume"] = "Fortsetzen",
        ["s.plive_stop"] = "Stoppen",
        ["s.plive_hint"] = "Live vom Handy — Steuerung wirkt in Sekunden (gleiches WLAN).",
        ["s.plive_stale"] = "Letzte Meldung {0} — Steuerung braucht das gleiche WLAN.",
        // PC 1.5.2: Transparenz — wie frisch ist die letzte Handy-Meldung
        ["s.plive_last_seen"] = "Letzte Meldung {0}",
        ["s.plive_busy"] = "Befehl gesendet — warte auf dein Handy…",
        ["s.plive_badge"] = "● LIVE",

        // PC 1.5.0: Handy-Vorrang + deutlich sichtbare Aufnahme
        ["s.dash_rec_badge"] = "● AUFNAHME LÄUFT",
        ["s.dash_priority_label"] = "HANDY HAT VORRANG",
        ["s.dash_phone_recording"] = "Dein Handy zeichnet auf",
        ["s.dash_phone_priority"] = "Der PC hält sich zurück, solange dein Handy aufzeichnet — es läuft immer nur eine Aufzeichnung, und dein Handy hat Vorrang.",

        ["s.upd_title"] = "AKTUALISIERUNGEN",
        ["s.upd_current"] = "Installierte Version {0}",
        ["s.upd_auto"] = "Wird bei jedem Start automatisch geprüft.",
        ["s.upd_check"] = "Nach Updates suchen",
        ["s.upd_checking"] = "Prüfe…",
        ["s.upd_uptodate"] = "Du bist auf dem neuesten Stand.",
        ["s.upd_available"] = "Version {0} ist verfügbar.",
        ["s.upd_notes"] = "Neu in dieser Version:",
        ["s.upd_download"] = "Herunterladen & neu starten",
        ["s.upd_downloading"] = "Lädt… {0} %",
        ["s.upd_installing"] = "Update wird eingespielt — die App startet gleich neu…",
        ["s.upd_manual"] = "Automatisches Update ist hier nicht möglich — bitte manuell herunterladen:",
        ["s.upd_error"] = "Update-Prüfung fehlgeschlagen (offline?).",
        ["s.upd_badge"] = "Update verfügbar",
        ["s.upd_open"] = "Release-Seite öffnen",
        ["s.upd_keep"] = "Deine Einstellungen und Aufzeichnungen bleiben erhalten — das Update ersetzt nur die Programmdateien.",
        // PC 1.5.1: ehrliches Fehlerband + Nachkontrolle
        ["s.upd_failed_title"] = "Das letzte Update ist nicht durchgelaufen",
        ["s.upd_failed_body"] = "Ziel war v{0}, es läuft aber noch v{1} — die Programmdateien wurden nicht ersetzt. Die Details stehen im Update-Protokoll (update.log).",
        ["s.upd_open_folder"] = "Log-Ordner öffnen",
        ["s.upd_dismiss"] = "Verstanden",
    };
}
