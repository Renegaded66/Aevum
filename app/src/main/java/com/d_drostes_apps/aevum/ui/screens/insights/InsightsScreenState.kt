package com.d_drostes_apps.aevum.ui.screens.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.components.GlassCard
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing

/**
 * M18.137 (Kanban t_386782be): Lade- und Fehlerdarstellung der
 * Statistik-Ansicht.
 *
 * WARUM ES DIESE DATEI GIBT — und warum sie NICHT am Aufklappen hängt:
 *
 * Card-Body und beide Vorgänger-Karten gingen davon aus, dass das Aufklappen
 * der Top-Liste einen Ladevorgang auslöst ("call the service to fetch all
 * activities", "loading states are shown appropriately"). In Aevum ist das
 * nicht so und kann es nicht sein: die vollständige Liste kommt aus dem
 * reaktiven Room-Flow und liegt beim Aufklappen bereits im
 * [InsightsUiState.allBreakdown]. Ein Spinner am Toggle hätte nichts zu
 * warten — er würde nur die Aufklapp-Animation überblenden und wäre wieder
 * weg, bevor das Auge ihn erfasst.
 *
 * Der Ladezustand gehört deshalb an den Screen-Aufbau, wo es tatsächlich
 * etwas zu warten gibt: den Kaltstart ([InsightsUiState.isLoading] ist nur
 * dann true) und einen fehlgeschlagenen Aufbau
 * ([InsightsUiState.errorMessage]).
 *
 * Die drei Phasen sind bewusst als [InsightsPhase] modelliert, damit sie
 * sich gegenseitig ausschließen: es kann nie gleichzeitig ein Fehler und
 * ein Spinner sichtbar sein, und eine fehlgeschlagene Auswertung wird nie
 * als "Noch keine Daten" missverstanden.
 */

/** Test-Tag des Lade-Platzhalters. */
internal const val INSIGHTS_LOADING_TEST_TAG = "insightsLoading"

/** Test-Tag der Fehlerkarte. */
internal const val INSIGHTS_ERROR_TEST_TAG = "insightsError"

/** Test-Tag des Retry-Buttons. */
internal const val INSIGHTS_RETRY_TEST_TAG = "insightsRetry"

/**
 * Test-Tag der scrollbaren Inhaltsliste des Screens.
 *
 * Notwendig fuer UI-Tests, die bis zur letzten Zeile der AUFGEKLAPPTEN Liste
 * pruefen wollen: in einer LazyColumn sind hintere Zeilen komponiert, aber
 * ausserhalb des Viewports — `assertIsDisplayed` scheitert dann, obwohl die
 * Zeile korrekt da ist. Die Tests scrollen darum gezielt zu ihr.
 */
internal const val INSIGHTS_LIST_TEST_TAG = "insightsList"

/**
 * Was der Screen in seinem Datenbereich zeigt.
 *
 * Reihenfolge der Prüfung ist Absicht: der Fehler schlägt alles. Ein Aufbau,
 * der fehlgeschlagen ist, bleibt fehlgeschlagen — ein gleichzeitig gesetztes
 * `isLoading` darf die Fehlermeldung nicht verdecken, sonst endet der Nutzer
 * in einem Spinner ohne `retry()`-Weg.
 */
internal enum class InsightsPhase {
    Loading,
    Error,
    Content
}

/**
 * Aktuelle Phase aus dem UiState ableiten. Reine Funktion → direkt testbar,
 * ohne Compose oder Robolectric.
 *
 * `isLoading && !hasData`: Ein zweiter Ladevorgang bei bereits vorhandenen
 * Daten (z.B. nach einem Sprachwechsel) soll den Inhalt NICHT wegnehmen —
 * sonst flackert der Screenshot-fähige Inhalt bei jedem Rebuild. Nur der
 * echte Kaltstart, bei dem noch nichts da ist, zeigt den Platzhalter.
 */
internal fun insightsPhaseOf(
    isLoading: Boolean,
    errorMessage: String?,
    hasData: Boolean
): InsightsPhase = when {
    errorMessage != null -> InsightsPhase.Error
    isLoading && !hasData -> InsightsPhase.Loading
    else -> InsightsPhase.Content
}

/**
 * Lade-Platzhalter für den Kaltstart.
 *
 * Bewusst KEIN Skeleton der späteren Zeilen: die Anzahl der Aktivitäten steht
 * beim Kaltstart noch nicht fest, ein Skeleton müsste also raten und würde
 * beim Eintreffen der echten Daten sichtbar springen. Ein einzelner ruhiger
 * Indikator in einer GlassCard bleibt im Glassmorphism-Stil des Screens und
 * verspricht nichts, was er noch nicht weiß.
 */
@Composable
internal fun InsightsLoadingCard(modifier: Modifier = Modifier) {
    GlassCard(accentColor = MaterialTheme.colorScheme.primary, modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(INSIGHTS_LOADING_TEST_TAG)
                .padding(vertical = AevumSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AevumSpacing.md)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = stringResource(R.string.insights_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Fehlerkarte mit echter Wiederherstellung.
 *
 * Der Button ruft [InsightsViewModel.retry] — und das ist hier wesentlich:
 * der Retry baut den kompletten Flow-Baum neu auf (siehe ViewModel), nicht
 * nur einen Zustand zurück. Ein Fehler beim Lesen der Tagespauschalen hinge
 * sonst am selben Fehler wieder fest und der Nutzer hätte keinen Weg zurück
 * außer die App neu zu starten.
 *
 * Farbe/Vokabular bewusst zurückhaltend: Aevum spricht in ruhigem Ton, und
 * ein fehlgeschlagener lokaler Lesevorgang ist kein Alarm, sondern eine
 * Situation mit einem klaren nächsten Schritt.
 */
@Composable
internal fun InsightsErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val accent = MaterialTheme.colorScheme.error
    GlassCard(accentColor = accent, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(INSIGHTS_ERROR_TEST_TAG)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CloudOff,
                        contentDescription = null,
                        tint = accent
                    )
                }
                Spacer(Modifier.width(AevumSpacing.md))
                Text(
                    text = stringResource(R.string.insights_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(Modifier.height(AevumSpacing.md))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )
            Spacer(Modifier.height(AevumSpacing.md))
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(AevumRadius.full))
                    .background(accent.copy(alpha = 0.14f))
                    .clickable(onClick = onRetry)
                    .testTag(INSIGHTS_RETRY_TEST_TAG)
                    .padding(horizontal = AevumSpacing.md, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = stringResource(R.string.insights_retry),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = accent
                )
            }
        }
    }
}
