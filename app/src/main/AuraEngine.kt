package com.laparole.aurastudio

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbFilter
import androidx.media3.transformer.*
import java.io.File

data class MediaRef(val file: File, val mime: String)

enum class Preset(val label: String, val photoUs: Long, val clipMs: Long) {
    DOUX("Doux", 3200000L, 5000L),
    VIF("Vif", 1500000L, 2500L),
    PROFOND("Profond", 2500000L, 4000L)
}

object AuraEngine {

    private const val MAX_SIDE = 1920

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val probe = BitmapFactory.Options()
        probe.inJustDecodeBounds = true
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, probe)
        }
        if (probe.outWidth <= 0) return null
        var sample = 1
        while (probe.outWidth / sample > MAX_SIDE || probe.outHeight / sample > MAX_SIDE) {
            sample *= 2
        }
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: return null
        var angle = 0
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { s ->
                when (ExifInterface(s).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> angle = 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> angle = 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> angle = 270
                }
            }
        }
        if (angle == 0) return bmp
        val m = Matrix()
        m.postRotate(angle.toFloat())
        val rot = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        if (rot != bmp) bmp.recycle()
        return rot
    }

    fun importMedia(context: Context, uris: List<Uri>): List<MediaRef> {
        val dir = File(context.filesDir, "m" + System.currentTimeMillis())
        dir.mkdirs()
        val out = mutableListOf<MediaRef>()
        uris.forEachIndexed { i, uri ->
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            if (mime.startsWith("video")) {
                val dest = File(dir, "m" + i + ".mp4")
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        dest.outputStream().use { inp.copyTo(it) }
                    }
                }
                if (dest.length() > 0) out.add(MediaRef(dest, "video/mp4"))
            } else {
                val dest = File(dir, "m" + i + ".jpg")
                runCatching {
                    val bmp = decodeScaled(context, uri)
                    if (bmp != null) {
                        dest.outputStream().use { o ->
                            bmp.compress(Bitmap.CompressFormat.JPEG, 90, o)
                        }
                        bmp.recycle()
                    }
                }
                if (dest.length() > 0) out.add(MediaRef(dest, "image/jpeg"))
            }
        }
        return out
    }

    fun importAudio(context: Context, uri: Uri): File? {
        val dest = File(context.filesDir, "music.m4a")
        return runCatching {
            context.contentResolver.openInputStream(uri)!!.use { inp ->
                dest.outputStream().use { inp.copyTo(it) }
            }
            dest
        }.getOrNull()
    }

    @OptIn(UnstableApi::class)
    private fun buildEffects(preset: Preset): List<Effect> {
        val list = mutableListOf<Effect>()
        list.add(Presentation.createForWidthAndHeight(
            1080, 1920, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP))
        if (preset == Preset.VIF) list.add(Contrast(0.3f))
        if (preset == Preset.PROFOND) list.add(RgbFilter.createGrayscaleFilter())
        return list
    }

    @OptIn(UnstableApi::class)
    fun export(
        context: Context,
        media: List<MediaRef>,
        music: File?,
        preset: Preset,
        onDone: (String) -> Unit
    ) {
        if (media.isEmpty()) { onDone("Aucun media"); return }
        val hasMusic = music != null && music.length() > 0
        val fx = buildEffects(preset)

        val items = media.map { ref ->
            val isImage = ref.mime.startsWith("image")
            val mb = MediaItem.Builder().setUri(Uri.fromFile(ref.file))
            if (isImage) {
                mb.setMimeType(ref.mime)
            } else {
                mb.setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(preset.clipMs).build())
            }
            val eb = EditedMediaItem.Builder(mb.build())
                .setEffects(Effects(emptyList(), fx))
            if (isImage) {
                eb.setDurationUs(preset.photoUs)
                eb.setFrameRate(30)
            }
            if (hasMusic && !isImage) eb.setRemoveAudio(true)
            eb.build()
        }

        val seqs = mutableListOf(EditedMediaItemSequence(items))
        if (hasMusic) {
            val track = EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(music))).build()
            seqs.add(EditedMediaItemSequence(listOf(track), true))
        }

        val comp = Composition.Builder(seqs).build()
        val tmp = File(context.cacheDir, "aura" + System.currentTimeMillis() + ".mp4")
        Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(c: Composition, r: ExportResult) {
                    val msg = runCatching { publish(context, tmp) }
                        .fold({ "EXISTENCE" }, { "Erreur: " + it.message })
                    onDone(msg)
                }
                override fun onError(c: Composition, r: ExportResult, e: ExportException) {
                    val cause = e.cause
                    val detail = if (cause != null)
                        cause.javaClass.simpleName + " / " + cause.message
                    else
                        e.message
                    onDone("Echec: " + detail)
                }
            })
            .build()
            .start(comp, tmp.absolutePath)
    }

    private fun publish(context: Context, src: File): Uri {
        val cr = context.contentResolver
        val v = ContentValues()
        v.put(MediaStore.Video.Media.DISPLAY_NAME, src.name)
        v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        if (Build.VERSION.SDK_INT >= 29) {
            v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/AURA")
            v.put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)!!
        cr.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
        if (Build.VERSION.SDK_INT >= 29) {
            val v2 = ContentValues()
            v2.put(MediaStore.Video.Media.IS_PENDING, 0)
            cr.update(uri, v2, null, null)
        }
        src.delete()
        return uri
    }
}
