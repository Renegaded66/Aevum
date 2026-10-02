# Play Store listing — text blocks (ENGLISH)

**Purpose:** Google requires the background-location disclosure to be
„within the app itself **as well as in the app description and website**" and
the core feature to be „prominently documented and promoted in the app's
description". The Play listing is **English** — these are the blocks to paste
into Play Console.

**Language note:** English is the app's default language
(`LanguageRepository.LANGUAGE_DEFAULT = "en"`), the fallback resource folder is
`values/` (English) and the German translation lives in `values-de/`.

---

## 1. Short description (max. 80 characters)

```
Automatic time tracker: places, drives and activities — no button pressing.
```
*(74 characters)*

**Alternative, if the location angle should be explicit:**
```
Aevum logs your day automatically — places, drives, walks, time line.
```
*(69 characters)*

---

## 2. Full description (max. 4000 characters)

```
Aevum captures your day — without you having to log anything.

You arrive home and work is finished. You start driving and the trip is
recorded. You go for a walk and the route lands in your day. Aevum turns these
events into a complete picture of your time, automatically.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
WHAT AEVUM DETECTS AUTOMATICALLY
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

• Place detection (geofences)
  Set up places like home, work or the gym. Entering and leaving starts or
  stops activities automatically.

• Drive detection
  Car trips are detected automatically and recorded with their route.

• Walks and bike rides
  Your journeys are recorded as activities automatically.

• Place timeline
  A history of the places you have been to — including places you had not
  saved before.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
WHY AEVUM NEEDS LOCATION IN THE BACKGROUND
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

Aevum collects location data to enable place detection, drive detection,
journey detection and the place timeline — even when the app is closed or not
in use.

Here is why: that is the whole point of the app. Place changes happen while
your phone is in your pocket and Aevum is not open. If you had to open the app
yourself every time you enter or leave a place, there would be no automation —
and Aevum would be nothing more than a manual time tracker.

Before the first access, Aevum shows an in-app disclosure dialog explaining
this use. Access only begins after your explicit consent. You can revoke the
permission at any time in the Android system settings.

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
YOUR DATA STAYS WITH YOU
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

• No Aevum server, no user accounts
• No advertising, no sharing with third parties, no selling of data
• All location data, activities and notes stay exclusively on your device
• Export and backup as a file — you decide where it goes

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
MORE FEATURES
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

• Start and stop activities manually
• Calendar rules: appointments start the matching activity
• Digital Balance: keep an eye on screen time
• Sleep tracking via Health Connect
• Insights: week, month and distribution of your time
• Home screen widgets
• Works fully offline

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
PERMISSIONS AT A GLANCE
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

• Location (foreground and background) — place detection, drive detection,
  journey detection, place timeline
• Activity recognition — motion detection (drives, walking, cycling)
• Notifications — running activities and alerts
• Calendar (read only) — optional calendar rules
• Usage access (optional) — Digital Balance
• Health Connect (optional) — sleep and workout data

Privacy policy: https://renegaded66.github.io/Aevum/
```

**Character count:** approx. 2,850 of 4,000 — room for adjustments.

---

## 3. What Google checks in the description

The reviewer searches for three things — all of them are in the text above:

| Requirement (Google's wording) | in the text above |
|---|---|
| „Include the term 'location'" | „Aevum collects **location data**" |
| „Indicate the nature of your app's use of location in the background" | „even when the app is **closed** or not in use", section „WHY AEVUM NEEDS LOCATION IN THE BACKGROUND" |
| „List all of the app features that use location in the background" | the four features individually, in that same paragraph |

The same four features are named identically in the app disclosure, in this
listing text and in the privacy policy — that consistency is what the review
looks for.

---

## 4. Screenshot recommendation

Google expects the core feature to be promoted visually as well. A screenshot
with a visible map or place list makes the location feature obvious to the
reviewer. Suggested order for the store screenshots:

1. Dashboard with today's activities
2. Places screen with map / geofence circles
3. Place timeline (history)
4. Weekly / monthly insights
5. Widgets on the home screen

---

## 5. Declaration form: "Main purpose" and "Location access"

For the background-location declaration form — **name only ONE feature**
(Google: „We can only evaluate one feature at a time. The inclusion of multiple
features will result in an app's rejection. **Approval will be granted for your
entire app, not just for this single feature.**").

The Play Console form is in English — use the English wording below.

### Field: "What is the main purpose of your app?"

```
Aevum is an automatic time tracker. The app records activities such as work,
exercise and sleep without the user having to start them manually. Its main
purpose is automatic, uninterrupted time tracking of the day: users should not
have to think about operating the app.
```

### Field: "Why does your app need access to the location in the background?"

```
Aevum uses background location for automatic drive detection: car trips are
recognised as activities and recorded with their route while the phone is in
the user's pocket and the app is closed.

Without background location the user would have to start and stop every trip
manually — the app's core function (automatic time tracking without user
interaction) would no longer exist. A trip is triggered by the change of
location itself; there is no other trigger available in the background.

All location data stays locally on the device. There is no server, no accounts
and no sharing with third parties.
```

**Why drive detection and not geofencing?** Two reasons: (1) geofencing is no
longer an approved foreground-service use case as of 26 Aug 2026, and (2) drive
detection is the strongest case — it has a clearly visible benefit (route, trip
duration), it demonstrably depends on the background, and it is not treated as a
convenience feature.

---

## 6. German listing (only if added later)

The German disclosure wording lives in
`app/src/main/res/values-de/strings_disclosure.xml`. If a German listing is
added, it must name the same four features as the app disclosure and this
English listing:

- Ortserkennung (Geofences)
- Fahrterkennung
- Erkennung von Spaziergängen und Radfahrten
- Orts-Timeline und unbekannte Orte
