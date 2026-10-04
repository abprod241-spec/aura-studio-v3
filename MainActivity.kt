package com.laparole.aurastudio

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.exitProcess

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val crashFile = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            runCatching { crashFile.writeText(e.stackTraceToString().take(2500)) }
            exitProcess(1)
        }
        val lastCrash = if (crashFile.exists()) crashFile.readText() else null
        crashFile.delete()
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (lastCrash != null) CrashScreen(lastCrash) else AuraScreen()
                }
            }
        }
    }
}

@Composable
fun CrashScreen(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())
    ) {
        Text(text = "Plantage precedent", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = text, fontSize = 11.sp)
    }
}

@Composable
fun AuraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var media by remember { mutableStateOf<List<MediaRef>>(emptyList()) }
    var music by remember { mutableStateOf<File?>(null) }
    var preset by remember { mutableStateOf(Preset.PROFOND) }
    var status by remember { mutableStateOf("Une intention devient une oeuvre.") }
    var busy by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(30)
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            busy = true
            status = "Reception..."
            scope.launch {
                val list = withContext(Dispatchers.IO) { AuraEngine.importMedia(context, uris) }
                media = list
                busy = false
                status = list.size.toString() + " / " + uris.size.toString() + " retenues"
            }
        }
    }

    val audioPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            busy = true
            scope.launch {
                val f = withContext(Dispatchers.IO) { AuraEngine.importAudio(context, uri) }
                music = f
                busy = false
                status = if (f != null) "Musique accueillie." else "Musique illisible."
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "AURA", style = MaterialTheme.typography.headlineLarge)
        Text(text = "Studio de l'instant", fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = status, textAlign = TextAlign.Center)
        if (busy) CircularProgressIndicator()

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                )
            }
        ) { Text(text = "MES IMAGES") }

        OutlinedButton(
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
            onClick = { audioPicker.launch("audio/*") }
        ) { Text(text = if (music == null) "MA MUSIQUE" else "MA MUSIQUE  \u2022") }

        if (music != null) {
            TextButton(
                enabled = !busy,
                onClick = { music = null; status = "Musique retiree." }
            ) { Text(text = "retirer la musique") }
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(text = "RESSENTIR", fontSize = 12.sp)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Preset.values().forEach { p ->
                if (p == preset) {
                    Button(enabled = !busy, onClick = { preset = p }) { Text(text = p.label) }
                } else {
                    OutlinedButton(enabled = !busy, onClick = { preset = p }) { Text(text = p.label) }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            enabled = !busy && media.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = {
                busy = true
                status = "Appel en cours..."
                AuraEngine.export(context, media, music, preset) { msg ->
                    busy = false
                    status = msg
                }
            }
        ) { Text(text = "APPEL", fontSize = 18.sp) }
    }
}
