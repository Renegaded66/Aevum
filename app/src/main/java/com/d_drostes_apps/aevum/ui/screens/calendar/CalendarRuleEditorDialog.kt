package com.d_drostes_apps.aevum.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import com.d_drostes_apps.aevum.data.model.ActivityType
import com.d_drostes_apps.aevum.data.model.CalendarOverlapPolicy
import com.d_drostes_apps.aevum.data.model.CalendarRule
import com.d_drostes_apps.aevum.data.model.CalendarRuleType
import com.d_drostes_apps.aevum.domain.calendar.CalendarMatchEngine
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.util.UUID

/**
 * M18.129: Der Regel-Editor.
 *
 * EIN Dialog für alle sieben Regeltypen: das Typ-Dropdown tauscht nur die
 * bedingungsspezifischen Felder aus, das Grundgerüst (Name, Aktivität,
 * Zeitfenster, Wochentage, Verhalten) bleibt identisch. Das ist bewusst
 * so — sieben separate Editoren wären für den Nutzer eine Lernhürde ohne
 * Mehrwert (Muster: bestehenden Editor erweitern statt neuen Screen bauen,
 * wie beim Todo-Editor in M18.38).
 *
 * ALLE Bedingungen sind sichtbar und in Klartext beschriftet. Nichts ist
 * versteckt oder still vorbelegt.
 */
@Composable
fun CalendarRuleEditorDialog(
    /** null = neue Regel. */
    existing: CalendarRule?,
    activityTypes: List<ActivityType>,
    calendars: List<CalendarOption>,
    onDismiss: () -> Unit,
    onSave: (CalendarRule) -> Unit
) {
    val isNew = existing == null
    // Form-State (bewusst MutableState statt ViewModel — der Dialog ist
    // ephemär; M18.26-Lektion: MutableStateFlow für Form-State).
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var matchType by remember { mutableStateOf(existing?.matchType ?: CalendarRuleType.ANY_FIELD_CONTAINS) }
    var matchValue by remember { mutableStateOf(existing?.matchValue ?: "") }
    var activityTypeId by remember { mutableStateOf(existing?.activityTypeId) }
    var defaultTitle by remember { mutableStateOf(existing?.defaultTitle ?: "") }
    var requireAllWords by remember { mutableStateOf(existing?.requireAllWords ?: false) }
    var titleOnly by remember { mutableStateOf(existing?.titleOnly ?: false) }
    var caseSensitive by remember { mutableStateOf(existing?.caseSensitive ?: false) }
    var minDuration by remember { mutableStateOf((existing?.minDurationMinutes ?: 0).toString()) }
    var weekdayMask by remember { mutableStateOf(existing?.weekdayMask ?: 0x7F) }
    var overlapPolicy by remember { mutableStateOf(existing?.overlapPolicy ?: CalendarOverlapPolicy.OVERRIDE) }
    var selectedCalendarIds by remember {
        mutableStateOf(CalendarMatchEngine.parseIds(existing?.matchCalendarIds).toSet())
    }
    var windowEnabled by remember {
        mutableStateOf((existing?.windowStartMinute ?: -1) >= 0 || (existing?.windowEndMinute ?: -1) >= 0)
    }
    var windowStart by remember {
        mutableStateOf(((existing?.windowStartMinute ?: -1).takeIf { it >= 0 } ?: (6 * 60)).toString())
    }
    var windowEnd by remember {
        mutableStateOf(((existing?.windowEndMinute ?: -1).takeIf { it >= 0 } ?: (22 * 60)).toString())
    }
    var typeDropdownOpen by remember { mutableStateOf(false) }
    var activityDropdownOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save() {
        // Validierung — ehrlich und konkret, kein stilles Ignorieren.
        if (name.isBlank()) {
            error = "Bitte gib der Regel einen Namen."
            return
        }
        if (activityTypeId == null) {
            error = "Bitte wähle die Aktivität, die aufgezeichnet werden soll."
            return
        }
        if (CalendarRuleType.needsMatchValue(matchType) && matchValue.isBlank()) {
            error = "Bitte gib den Suchbegriff ein."
            return
        }
        if (matchType == CalendarRuleType.CALENDAR_IS && selectedCalendarIds.isEmpty()) {
            // Leere Auswahl bedeutet "alle Kalender" — das ist gültig,
            // aber wir machen es transparent statt still.
            error = null
        }
        val rule = CalendarRule(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim(),
            enabled = existing?.enabled ?: true,
            matchType = matchType,
            matchValue = matchValue.trim(),
            matchCalendarIds = if (matchType == CalendarRuleType.CALENDAR_IS) {
                CalendarMatchEngine.serializeIds(selectedCalendarIds)
            } else null,
            caseSensitive = caseSensitive,
            requireAllWords = requireAllWords,
            titleOnly = titleOnly,
            activityTypeId = activityTypeId,
            defaultTitle = defaultTitle.trim().takeIf { it.isNotEmpty() },
            minDurationMinutes = minDuration.toIntOrNull()?.coerceAtLeast(0) ?: 0,
            windowStartMinute = if (windowEnabled) parseTimeToMinute(windowStart) else -1,
            windowEndMinute = if (windowEnabled) parseTimeToMinute(windowEnd) else -1,
            weekdayMask = weekdayMask,
            overlapPolicy = overlapPolicy,
            priority = existing?.priority ?: 0,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        onSave(rule)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isNew) "Neue Kalender-Regel" else "Regel bearbeiten",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                // ── 1. Name ───────────────────────────────────────────
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name der Regel") },
                    placeholder = { Text("z.B. Studium aus Kalender") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // ── 2. Bedingung: Typ ─────────────────────────────────
                SectionLabel("Bedingung")
                Box {
                    OutlinedRow(
                        text = ruleTypeLabel(matchType),
                        onClick = { typeDropdownOpen = true }
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = typeDropdownOpen,
                        onDismissRequest = { typeDropdownOpen = false }
                    ) {
                        CalendarRuleType.ALL.forEach { type ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(ruleTypeLabel(type)) },
                                onClick = {
                                    matchType = type
                                    typeDropdownOpen = false
                                    error = null
                                }
                            )
                        }
                    }
                }

                // ── 3. Bedingung: Wert (typ-abhängig) ─────────────────
                when (matchType) {
                    CalendarRuleType.CALENDAR_IS -> {
                        Text(
                            "Kalender auswählen (keine Auswahl = alle Kalender)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (calendars.isEmpty()) {
                            Text(
                                "Keine Kalender gefunden — Berechtigung prüfen.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        calendars.forEach { cal ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedCalendarIds = if (selectedCalendarIds.contains(cal.id)) {
                                            selectedCalendarIds - cal.id
                                        } else {
                                            selectedCalendarIds + cal.id
                                        }
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = selectedCalendarIds.contains(cal.id),
                                    onCheckedChange = { checked ->
                                        selectedCalendarIds = if (checked) {
                                            selectedCalendarIds + cal.id
                                        } else {
                                            selectedCalendarIds - cal.id
                                        }
                                    }
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(cal.name, fontSize = 14.sp)
                                    if (cal.account.isNotBlank()) {
                                        Text(
                                            cal.account,
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    CalendarRuleType.ALL_DAY_ONLY -> {
                        Text(
                            "Trifft nur ganztägige Termine (z.B. Urlaub, Feiertage).",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    CalendarRuleType.TITLE_REGEX -> {
                        OutlinedTextField(
                            value = matchValue,
                            onValueChange = { matchValue = it; error = null },
                            label = { Text("Regulärer Ausdruck (Titel)") },
                            placeholder = { Text("^VL\\s+\\d+") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            "Erweitert: nur nutzen, wenn einfache Wörter nicht reichen.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    else -> {
                        OutlinedTextField(
                            value = matchValue,
                            onValueChange = { matchValue = it; error = null },
                            label = {
                                Text(
                                    when (matchType) {
                                        CalendarRuleType.TITLE_CONTAINS -> "Wörter im Titel"
                                        CalendarRuleType.DESCRIPTION_CONTAINS -> "Wörter in der Beschreibung"
                                        CalendarRuleType.ATTENDEE_CONTAINS -> "Teilnehmer enthält"
                                        else -> "Wörter (Titel oder Beschreibung)"
                                    }
                                )
                            },
                            placeholder = { Text("Vorlesung, Übung") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text(
                            "Mehrere Wörter mit Komma trennen.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // ── 4. Wort-Logik (nur bei Wortsuche) ─────────────────
                if (matchType == CalendarRuleType.ANY_FIELD_CONTAINS ||
                    matchType == CalendarRuleType.TITLE_CONTAINS ||
                    matchType == CalendarRuleType.DESCRIPTION_CONTAINS
                ) {
                    ToggleRow(
                        title = "Alle Wörter müssen vorkommen",
                        subtitle = "Aus: ein Treffer genügt (ODER). An: alle Wörter nötig (UND).",
                        checked = requireAllWords,
                        onCheckedChange = { requireAllWords = it }
                    )
                }
                if (matchType == CalendarRuleType.ANY_FIELD_CONTAINS && !titleOnly) {
                    ToggleRow(
                        title = "Groß-/Kleinschreibung beachten",
                        subtitle = "Aus: „vorlesung“ findet auch „Vorlesung“.",
                        checked = caseSensitive,
                        onCheckedChange = { caseSensitive = it }
                    )
                }

                // ── 5. Aktion: Aktivität ──────────────────────────────
                SectionLabel("Aktion")
                Box {
                    OutlinedRow(
                        text = activityTypes.firstOrNull { it.id == activityTypeId }
                            ?.let { "${it.icon} ${it.name}" }
                            ?: "Aktivität wählen…",
                        onClick = { activityDropdownOpen = true }
                    )
                    androidx.compose.material3.DropdownMenu(
                        expanded = activityDropdownOpen,
                        onDismissRequest = { activityDropdownOpen = false }
                    ) {
                        activityTypes.forEach { type ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("${type.icon}  ${type.name}") },
                                onClick = {
                                    activityTypeId = type.id
                                    activityDropdownOpen = false
                                    error = null
                                }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = defaultTitle,
                    onValueChange = { defaultTitle = it },
                    label = { Text("Eigener Titel (optional)") },
                    placeholder = { Text("Leer = Name der Aktivität") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // ── 6. Verhalten bei Überschneidung ───────────────────
                ToggleRow(
                    title = "Laufende Aufzeichnung beenden",
                    subtitle = "An: der Termin übernimmt (Standard). Aus: nur starten, wenn nichts läuft.",
                    checked = overlapPolicy == CalendarOverlapPolicy.OVERRIDE,
                    onCheckedChange = {
                        overlapPolicy = if (it) CalendarOverlapPolicy.OVERRIDE else CalendarOverlapPolicy.ONLY_IF_IDLE
                    }
                )

                // ── 7. Filter ─────────────────────────────────────────
                SectionLabel("Filter (optional)")

                OutlinedTextField(
                    value = minDuration,
                    onValueChange = { minDuration = it.filter { c -> c.isDigit() } },
                    label = { Text("Mindestdauer in Minuten (0 = egal)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                ToggleRow(
                    title = "Nur zu bestimmten Zeiten",
                    subtitle = "Termine außerhalb dieses Fensters werden ignoriert.",
                    checked = windowEnabled,
                    onCheckedChange = { windowEnabled = it }
                )
                if (windowEnabled) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
                    ) {
                        OutlinedTextField(
                            value = windowStart,
                            onValueChange = { windowStart = it },
                            label = { Text("Von") },
                            placeholder = { Text("06:00") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = windowEnd,
                            onValueChange = { windowEnd = it },
                            label = { Text("Bis") },
                            placeholder = { Text("22:00") },
                            modifier = Modifier.weight(1f),
                            singleLine = true
                        )
                    }
                }

                Text("Wochentage", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                WeekdayChips(
                    mask = weekdayMask,
                    onChange = { weekdayMask = it }
                )

                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                }
                Spacer(Modifier.height(AevumSpacing.xs))
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }) {
                Text(if (isNew) "Hinzufügen" else "Speichern", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Abbrechen") }
        }
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun OutlinedRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AevumRadius.sm))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .clickable(onClick = onClick)
            .padding(horizontal = AevumSpacing.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, modifier = Modifier.weight(1f), fontSize = 15.sp)
        Icon(Icons.Filled.ArrowDropDown, contentDescription = "Auswahl öffnen")
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Wochentags-Auswahl als Bitmaske (Mo=1 … So=64). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun WeekdayChips(mask: Int, onChange: (Int) -> Unit) {
    val labels = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label ->
            val bit = 1 shl index
            val selected = (mask and bit) != 0
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                    .clickable {
                        onChange(if (selected) mask and bit.inv() else mask or bit)
                    }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Klartext-Label für einen Regeltyp. */
fun ruleTypeLabel(type: String): String = when (type) {
    CalendarRuleType.ANY_FIELD_CONTAINS -> "Wort in Titel oder Beschreibung"
    CalendarRuleType.TITLE_CONTAINS -> "Wort nur im Titel"
    CalendarRuleType.DESCRIPTION_CONTAINS -> "Wort nur in der Beschreibung"
    CalendarRuleType.TITLE_REGEX -> "Regulärer Ausdruck im Titel"
    CalendarRuleType.CALENDAR_IS -> "Bestimmte Kalender"
    CalendarRuleType.ATTENDEE_CONTAINS -> "Teilnehmer enthält"
    CalendarRuleType.ALL_DAY_ONLY -> "Nur ganztägige Termine"
    else -> "Unbekannt"
}

/** "06:30" → 390 Minuten seit Mitternacht. Ungültiges → -1. */
private fun parseTimeToMinute(raw: String): Int {
    val parts = raw.trim().split(":")
    if (parts.size != 2) return -1
    val h = parts[0].toIntOrNull() ?: return -1
    val m = parts[1].toIntOrNull() ?: return -1
    if (h !in 0..23 || m !in 0..59) return -1
    return h * 60 + m
}
