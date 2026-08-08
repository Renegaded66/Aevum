package com.d_drostes_apps.aevum.ui.screens.bucketlist

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.d_drostes_apps.aevum.ui.components.AevumCard
import com.d_drostes_apps.aevum.ui.components.CardVariant
import com.d_drostes_apps.aevum.ui.theme.AevumSpacing
import java.io.File
import java.io.FileOutputStream
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.width

/**
 * M18.39: Bucket-List-Editor — neuer Eintrag oder bestehenden bearbeiten.
 *
 * Felder: Titel (Pflicht), Ort, Icon (Emoji), Kategorie, optionales
 * Zieldatum (JJJJ-MM-TT), Notizen, optionales Bild (aus Galerie, wird
 * in den App-Speicher kopiert — kein Coil noetig, BitmapFactory reicht).
 */
@Composable
fun BucketListEditorScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    itemId: String? = null,
    viewModel: BucketListEditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(itemId) {
        if (itemId != null) viewModel.loadItem(itemId)
    }

    // Bild aus Galerie waehlen -> in App-Speicher kopieren
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val savedPath = copyImageToAppStorage(context, uri)
            if (savedPath != null) viewModel.setImagePath(savedPath)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = AevumSpacing.md, vertical = AevumSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)
    ) {
        item {
            AevumCard(variant = CardVariant.Gradient) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(AevumSpacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            if (itemId != null) "BUCKET LIST BEARBEITEN" else "NEUER BUCKET LIST EINTRAG",
                            fontSize = 11.sp, letterSpacing = 1.1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            if (itemId != null) "Traum anpassen" else "Neuer Traum",
                            fontSize = 28.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    TextButton(onClick = onBack) { Text("Abbrechen") }
                }
            }
        }

        item {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text("Was willst du machen?", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.title,
                        onValueChange = viewModel::setTitle,
                        label = { Text("z.B. Nordlichter in Island sehen") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        item {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.md)) {
                    Text("Details", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value = state.location,
                        onValueChange = viewModel::setLocation,
                        label = { Text("Ort (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                        OutlinedTextField(
                            value = state.icon,
                            onValueChange = viewModel::setIcon,
                            label = { Text("Icon") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = state.category,
                            onValueChange = viewModel::setCategory,
                            label = { Text("Kategorie") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = state.targetDate,
                        onValueChange = viewModel::setTargetDate,
                        label = { Text("Zieldatum (optional, JJJJ-MM-TT)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = viewModel::setNotes,
                        label = { Text("Notizen (optional)") },
                        modifier = Modifier.fillMaxWidth().height(100.dp)
                    )
                    // M18.43: Schwierigkeitsgrad (1-5 Sterne) — bestimmt die
                    // XP-Belohnung beim Abhaken (10-50 XP).
                    Text("Schwierigkeit (bestimmt die XP-Belohnung)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..5).forEach { star ->
                            val filled = star <= state.difficulty
                            Text(
                                if (filled) "★" else "☆",
                                fontSize = 30.sp,
                                color = if (filled) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { viewModel.setDifficulty(star) }
                                    .padding(2.dp)
                            )
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "+${state.difficulty * 10} XP",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFFFB300)
                        )
                    }
                }
            }
        }

        item {
            AevumCard {
                Column(verticalArrangement = Arrangement.spacedBy(AevumSpacing.sm)) {
                    Text("Bild (optional)", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    if (state.imagePath != null) {
                        val bmp = remember(state.imagePath) {
                            runCatching { BitmapFactory.decodeFile(state.imagePath) }.getOrNull()
                        }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Bucket-List-Bild",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp)
                                    .clip(RoundedCornerShape(14.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        TextButton(onClick = { viewModel.setImagePath(null) }) { Text("Bild entfernen") }
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .clickable { imagePicker.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🖼️ Bild aus Galerie wählen", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        item {
            Button(
                onClick = {
                    viewModel.save()
                    onSaved()
                },
                enabled = state.title.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (itemId != null) "Änderungen speichern" else "Eintrag speichern") }
        }
        item { Spacer(Modifier.height(AevumSpacing.xxl)) }
    }
}

/** Bild aus Content-URI in den App-Speicher kopieren (kein Coil noetig). */
private fun copyImageToAppStorage(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        val dir = File(context.filesDir, "bucket_images").apply { mkdirs() }
        val target = File(dir, "bucket_${System.currentTimeMillis()}.jpg")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        target.absolutePath
    }.getOrNull()
}
