package com.d_drostes_apps.aevum.ui.screens.onboarding

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.R
import com.d_drostes_apps.aevum.ui.disclosure.PermissionDisclosureDialog
import com.d_drostes_apps.aevum.ui.disclosure.PermissionDisclosureKind
import com.d_drostes_apps.aevum.ui.disclosure.rememberPermissionDisclosureGate
import com.d_drostes_apps.aevum.ui.theme.AevumRadius
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import com.d_drostes_apps.aevum.util.LocaleHelper
import kotlinx.coroutines.launch

/**
 * M34 — Willkommens-Dialog: fünf ruhige Seiten mit Zurück/Vor, Punkten und
 * einem Abschluss, der die Auswahl wirklich anwendet.
 *
 * ── WARUM EIGENE RESSOURCEN STATT `stringResource` ───────────────────────
 * Seite 1 WÄHLT die Sprache, die restlichen Seiten sollen sofort in dieser
 * Sprache erscheinen. `stringResource` liest über den `LocalContext`, dessen
 * Ressourcen erst bei einem Activity-Recreate die neue Konfiguration sehen —
 * ein Recreate würde aber den Wizard-Zustand (Seite, Auswahl) verlieren.
 * Deshalb wird pro Sprache ein Ressourcen-Objekt erzeugt
 * ([LocaleHelper.resourcesFor]) und über [LocalOnboardingRes] verteilt. Die
 * Texte wechseln dadurch ohne Recreate, und der Wechsel ist sofort sichtbar —
 * genau der Effekt, der die Sprachwahl auf Seite 1 glaubwürdig macht.
 */
internal val LocalOnboardingRes = staticCompositionLocalOf<android.content.res.Resources> {
    error("Onboarding-Ressourcen nicht bereitgestellt")
}

/** Text aus den Onboarding-Ressourcen (Sprache der aktuellen Wizard-Wahl). */
@Composable
internal fun oStr(@StringRes id: Int, vararg formatArgs: Any): String {
    val resources = LocalOnboardingRes.current
    return if (formatArgs.isEmpty()) resources.getString(id) else resources.getString(id, *formatArgs)
}

@Composable
fun OnboardingFlowScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val resources = remember(state.language) {
        LocaleHelper.resourcesFor(context, state.language)
    }
    val scope = rememberCoroutineScope()
    var finishing by remember { mutableStateOf(false) }
    // M35: Auch der Nutzungszugriff im Wizard läuft über denselben
    // Offenlegungs-Dialog wie in den Einstellungen. Die Wizard-Seite
    // erklärt die Auswahl bereits — der Dialog ist die verbindliche
    // Bestätigung vor dem Sprung in die Systemeinstellungen.
    val disclosureGate = rememberPermissionDisclosureGate()

    // Der Wizard hat seine eigene Ressourcen-Quelle (siehe Klassendoku).
    CompositionLocalProvider(LocalOnboardingRes provides resources) {
        val pagerState = rememberPagerState(
            initialPage = state.page,
            pageCount = { OnboardingViewModel.PAGE_COUNT }
        )

        // ── Abschluss ─────────────────────────────────────────────────────
        // 1. Auswahl anwenden (suspend), 2. Sprache global setzen,
        // 3. Berechtigungen anfragen, 4. erst dann zum Dashboard.
        val afterPermissions: () -> Unit = {
            val chosen = viewModel.state.value.permissions
            // Der Wizard ist die Offenlegung: Wurde der Standort hier erteilt,
            // gilt die Standort-Offenlegung als bestätigt. Sonst würde beim
            // nächsten Start derselbe Dialog erneut erscheinen, obwohl der
            // Nutzer gerade zugestimmt hat.
            if ("location" in chosen &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                com.d_drostes_apps.aevum.ui.disclosure.LocationDisclosure
                    .markAccepted(context)
            }
            // Nutzungszugriff ist KEINE Runtime-Berechtigung — Android zeigt
            // dafür nur eine Systemseite. Nur wenn der Nutzer sie ausdrücklich
            // eingeschaltet hat, wird sie geöffnet.
            if ("usage" in chosen && !hasUsageAccess(context)) {
                disclosureGate.request(
                    kind = PermissionDisclosureKind.USAGE_ACCESS,
                    alreadyGranted = false
                ) {
                    openUsageAccessSettings(context)
                    onFinished()
                }
            } else {
                onFinished()
            }
        }

        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { afterPermissions() }

        val finish: () -> Unit = {
            if (!finishing) {
                finishing = true
                scope.launch {
                    viewModel.commit()
                    LocaleHelper.applyLocale(context, viewModel.state.value.language)
                    val wanted = runtimePermissionsFor(
                        context,
                        viewModel.state.value.permissions
                    )
                    if (wanted.isEmpty()) {
                        afterPermissions()
                    } else {
                        permissionLauncher.launch(wanted.toTypedArray())
                    }
                }
            }
        }

        // Pager ⇄ ViewModel: beide Richtungen, ohne Rückkopplung
        // (jede Seite prüft vor dem Setzen, ob sie schon synchron ist).
        LaunchedEffect(state.page) {
            if (pagerState.currentPage != state.page) {
                pagerState.animateScrollToPage(state.page)
            }
        }
        LaunchedEffect(pagerState.currentPage) {
            if (state.page != pagerState.currentPage) {
                viewModel.goTo(pagerState.currentPage)
            }
        }

        BackHandler(enabled = state.page > 0) { viewModel.back() }

        OnboardingSurface {
            Column(Modifier.fillMaxSize()) {
                OnboardingTopBar(page = pagerState.currentPage)

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    pageSpacing = 0.dp,
                    beyondViewportPageCount = 1
                ) { page ->
                    when (page) {
                        0 -> LanguagePage(
                            selected = state.language,
                            onSelect = viewModel::selectLanguage
                        )

                        1 -> FocusPage(
                            state = state,
                            onToggleCategory = viewModel::toggleCategory,
                            onToggleActivity = viewModel::toggleActivity,
                            onAddCategory = viewModel::addCategory,
                            onAddActivity = viewModel::addActivity
                        )

                        2 -> MascotPage(
                            state = state,
                            onEnable = viewModel::setMascotEnabled,
                            onColor = viewModel::setMascotColor,
                            onAntenna = viewModel::setMascotAntenna,
                            onCheeks = viewModel::setMascotCheeks
                        )

                        3 -> PermissionsPage(
                            state = state,
                            onToggle = viewModel::togglePermission
                        )

                        else -> TrustPage()
                    }
                }

                OnboardingFooter(
                    page = pagerState.currentPage,
                    finishing = finishing,
                    onBack = viewModel::back,
                    onNext = {
                        if (state.page == OnboardingViewModel.PAGE_COUNT - 1) finish()
                        else viewModel.next()
                    }
                )
            }
        }

        disclosureGate.pending?.let { pending ->
            PermissionDisclosureDialog(
                kind = pending.kind,
                requiresSettings = pending.requiresSettings,
                onAllow = {
                    disclosureGate.consent {
                        openUsageAccessSettings(context)
                        onFinished()
                    }
                },
                onDecline = {
                    // „Nicht jetzt" darf den Abschluss nicht blockieren.
                    disclosureGate.dismiss()
                    onFinished()
                }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// Gerüst: Hintergrund, Kopfzeile, Punkte, Fußzeile
// ════════════════════════════════════════════════════════════════════════

/**
 * Ruhiger Verlauf statt Vollton: oben ein weiches Eisblau-Licht, das nach
 * unten in die Fläche ausläuft. Kein Muster, keine Bewegung — die Seiten
 * sollen atmen, nicht ablenken.
 */
@Composable
internal fun OnboardingSurface(content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.background,
                        colors.surface.copy(alpha = 0.92f),
                        colors.background
                    )
                )
            )
    ) {
        // Ein einzelner Lichtkegel oben — gibt der dunklen Fläche Tiefe.
        // Bewusst ein vertikaler Verlauf statt eines radialen: er ist
        // auflösungsunabhängig (kein Pixel-Radius) und wirkt auf jedem
        // Seitenverhältnis gleich ruhig.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            colors.primary.copy(alpha = 0.14f),
                            colors.primary.copy(alpha = 0.04f),
                            Color.Transparent
                        )
                    )
                )
        )
        content()
    }
}

@Composable
internal fun OnboardingTopBar(page: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = AevumSpacing.lg, vertical = AevumSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "AEVUM",
            color = MaterialTheme.colorScheme.primary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 4.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = oStr(R.string.ob_step, page + 1, OnboardingViewModel.PAGE_COUNT),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            fontSize = 11.sp,
            letterSpacing = 1.sp
        )
    }
}

/**
 * Punkte-Indikator: leer = nicht erreicht, voll = aktuelle Seite, halb =
 * bereits gesehen. Der aktuelle Punkt wächst zu einer kleinen Kapsel — die
 * Bewegung ist die einzige Animation im Fuß und macht den Fortschritt
 * lesbar, ohne Zahlen zu wiederholen.
 */
@Composable
internal fun StepDots(page: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(OnboardingViewModel.PAGE_COUNT) { index ->
            val active = index == page
            val width by animateDpAsState(
                targetValue = if (active) 26.dp else 7.dp,
                animationSpec = tween(260),
                label = "dot-width-$index"
            )
            val color by animateColorAsState(
                targetValue = when {
                    active -> MaterialTheme.colorScheme.primary
                    index < page -> MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
                    else -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
                },
                animationSpec = tween(260),
                label = "dot-color-$index"
            )
            Box(
                modifier = Modifier
                    .height(7.dp)
                    .width(width)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

@Composable
internal fun OnboardingFooter(
    page: Int,
    finishing: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val isLast = page == OnboardingViewModel.PAGE_COUNT - 1
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = AevumSpacing.lg,
                end = AevumSpacing.lg,
                top = AevumSpacing.md,
                bottom = AevumSpacing.lg
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.lg)
    ) {
        StepDots(page)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(visible = page > 0) {
                OnboardingGhostButton(
                    text = oStr(R.string.ob_back),
                    onClick = onBack,
                    modifier = Modifier.padding(end = AevumSpacing.sm)
                )
            }
            OnboardingPrimaryButton(
                text = if (isLast) oStr(R.string.ob_trust_start) else oStr(R.string.ob_next),
                enabled = !finishing,
                onClick = onNext,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════
// Wiederverwendbare Bausteine (auch von OnboardingPages.kt genutzt)
// ════════════════════════════════════════════════════════════════════════

/** Kopf einer Seite: Eyebrow, Titel, Untertitel. Ein Ort für alle drei. */
@Composable
internal fun OnboardingPageHeader(
    eyebrow: String,
    title: String,
    subtitle: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
        Text(
            // Bewusst KEIN uppercase(): Großschreibung über die Laufzeit-
            // Locale würde Akzente und türkische Sonderfälle (i/İ) verändern.
            // Die Übersetzungen bringen ihre eigene Schreibweise mit.
            text = eyebrow,
            color = MaterialTheme.colorScheme.primary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp
        )
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 27.sp,
            lineHeight = 33.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = subtitle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.5.sp,
            lineHeight = 20.sp
        )
    }
}

/** Kleine Überschrift innerhalb einer Seite. */
@Composable
internal fun OnboardingSectionLabel(text: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.weight(1f))
        if (trailing != null) {
            Text(
                text = trailing,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
internal fun OnboardingPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(AevumRadius.full)
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(shape)
            .background(
                if (enabled) {
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                        )
                    )
                } else {
                    Brush.horizontalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (enabled) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun OnboardingGhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(54.dp)
            .clip(RoundedCornerShape(AevumRadius.full))
            .clickable(onClick = onClick)
            .padding(horizontal = AevumSpacing.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Berechtigungen: Anfrage-Liste ─────────────────────────────────────────

/**
 * Übersetzt die Auswahl der vierten Seite in echte Runtime-Berechtigungen.
 *
 * Bereits erteilte Berechtigungen werden NICHT erneut angefragt (Android
 * würde sie ohnehin still bestätigen, aber der Systemdialog soll gar nicht
 * erst erscheinen).
 *
 * Hintergrund-Standort fehlt hier bewusst: Android 11+ verlangt dafür einen
 * eigenen, zweiten Dialog NACH dem Vordergrund-Grant, und die Play-Richtlinie
 * verlangt dafür eine gesonderte Offenlegung. Der Wizard fragt deshalb nur
 * den Vordergrund-Standort an; „Immer erlauben" bietet die App danach an der
 * Stelle an, an der es gebraucht wird (Trigger & Erkennung).
 */
private fun runtimePermissionsFor(context: Context, chosen: Set<String>): List<String> {
    val result = mutableListOf<String>()

    fun add(permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            result += permission
        }
    }

    if ("location" in chosen) {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
    }
    if ("activity" in chosen) add(Manifest.permission.ACTIVITY_RECOGNITION)
    if ("notifications" in chosen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if ("calendar" in chosen) add(Manifest.permission.READ_CALENDAR)
    if ("calls" in chosen) add(Manifest.permission.READ_PHONE_STATE)
    // "usage" läuft über eine Systemseite, nicht über diesen Dialog.
    return result
}

private fun hasUsageAccess(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? android.app.AppOpsManager
        ?: return false
    val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
    }
    return mode == android.app.AppOpsManager.MODE_ALLOWED
}

private fun openUsageAccessSettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .setData(Uri.parse("package:${context.packageName}"))
        )
    } catch (e: Exception) {
        // Manche Geräte kennen die Seite nicht — dann bleibt es beim
        // normalen Einstellungs-Screen der App.
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}