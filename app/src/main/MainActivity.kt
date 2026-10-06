package com.laparole.aurastudio

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
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

class MainActivity : ComponentActivity() {

    private fun saveCrash(e: Throwable) {
        val txt = e.stackTraceToString().take(3000)
        runCatching { File(filesDir, "crash.txt").writeText(txt) }
        runCatching {
            val cv = ContentValues()
            cv.put(MediaStore.Downloads.DISPLAY_NAME, "AURA_CRASH.txt")
            cv.put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            if (Build.VERSION.SDK_INT >= 29) {
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val u = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            if (u != null) {
                contentResolver.openOutputStream(u)?.use { it.write(txt.toByteArray()) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            saveCrash(e)
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        super.onCreate(savedInstanceState)

        val f = File(filesDir, "crash.txt")
        val last = if (f.exists()) f.readText() else null
        f.delete()

        try {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        if (last != null) Report(last) else AuraScreen()
                    }
                }
            }
        } catch (e: Throwable) {
            saveCrash(e)
            setContent { Report(e.stackTraceToString().take(3000)) }
        }
    }
}

@Composable
fun Report(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())
    ) {
        Text(text = "RAPPORT", fontSize = 16.sp)
        Text(text = "copie aussi dans Telechargements / AURA_CRASH.txt", fontSize = 10.sp)
        Spacer(modifier = Modifier.height(10.dp))
        Text(text = text, fontSize = 10.sp)
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
                val fi = withContext(Dispatchers.IO) { AuraEngine.importAudio(context, uri) }
                music = fi
                busy = false
                status = if (fi != null) "Musique accueillie." else "Musique illisible."
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = "AURA", fontSize = 34.sp)
        Text(text = "Studio de l'instant", fontSize = 13.sp)
        Text(text = status, textAlign = TextAlign.Center)
        if (busy) CircularProgressIndicator()

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
        ) { Text(text = if (music == null) "MA MUSIQUE" else "MA MUSIQUE  *") }

        if (music != null) {
            TextButton(
                enabled = !busy,
                onClick = { music = null; status = "Musique retiree." }
            ) { Text(text = "retirer la musique") }
        }

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
