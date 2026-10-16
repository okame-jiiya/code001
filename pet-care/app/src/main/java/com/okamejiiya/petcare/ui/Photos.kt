package com.okamejiiya.petcare.ui

import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.okamejiiya.petcare.data.Images
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** 切り抜きの形 */
enum class CropAspect(val label: String, val ratio: Float?) {
    ORIGINAL("元の形", null),
    PORTRAIT("縦長", 3f / 4f),
    LANDSCAPE("横長", 4f / 3f),
    SQUARE("正方形", 1f),
}

/**
 * 写真の使い道ごとの大きさ。家族共有のクラウドに置くので軽くする
 * （書類は文字がなんとか読めるくらい、トイレは様子がわかるくらい）。
 */
enum class PhotoPurpose(val maxSide: Int, val quality: Int, val maxBytes: Int, val aspects: List<CropAspect>) {
    TOP(640, 80, 150_000, listOf(CropAspect.SQUARE)),
    DOCUMENT(1400, 50, 450_000, listOf(CropAspect.ORIGINAL, CropAspect.PORTRAIT, CropAspect.LANDSCAPE, CropAspect.SQUARE)),
    TOILET(800, 55, 150_000, listOf(CropAspect.ORIGINAL, CropAspect.SQUARE, CropAspect.LANDSCAPE, CropAspect.PORTRAIT)),
}

/** 保存した写真を表示する。端末に無ければ家族共有のクラウドから取ってくる */
@Composable
fun PhotoImage(id: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, maxSide: Int = 1024) {
    val photos = LocalPhotos.current
    val version by photos.version.collectAsState()
    val bitmap by produceState<ImageBitmap?>(null, id, version) {
        value = photos.load(id, maxSide)?.asImageBitmap() ?: value
    }
    Box(modifier.background(Pastel.LavenderLight), contentAlignment = Alignment.Center) {
        val b = bitmap
        if (b != null) Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = contentScale)
        else Text("🖼", color = Pastel.InkSoft)
    }
}

/** 「写真を選ぶ」「撮る」のボタン。選んだあと切り抜きと圧縮をして、保存した写真の ID を返す */
@Composable
fun PhotoButtons(purpose: PhotoPurpose, onPhoto: (String) -> Unit, pickLabel: String = "🖼 写真を選ぶ") {
    val context = LocalContext.current
    val photos = LocalPhotos.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }

    fun open(uri: Uri) {
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { Images.decode(context, uri) }
            if (bmp == null) Toast.makeText(context, "写真を読み込めませんでした", Toast.LENGTH_LONG).show()
            source = bmp
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { open(it) } }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) cameraUri?.let { open(Uri.parse(it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
            Text(pickLabel)
        }
        OutlinedButton(onClick = {
            val file = File(context.cacheDir, "shared/camera.jpg").apply { parentFile?.mkdirs() }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
            cameraUri = uri.toString()
            runCatching { camera.launch(uri) }.onFailure {
                Toast.makeText(context, "カメラを起動できませんでした", Toast.LENGTH_LONG).show()
            }
        }) { Text("📷 撮る") }
    }
    source?.let { bmp ->
        PhotoCropDialog(bmp, purpose, onDismiss = { source = null }) { bytes ->
            source = null
            onPhoto(photos.add(bytes))
        }
    }
}

/** 写真の切り抜き。指で動かす・2本指で拡大縮小・90度回転ができる */
@Composable
fun PhotoCropDialog(source: Bitmap, purpose: PhotoPurpose, onDismiss: () -> Unit, onDone: (ByteArray) -> Unit) {
    var bmp by remember { mutableStateOf(source) }
    var aspect by remember { mutableStateOf(purpose.aspects.first()) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var busy by remember { mutableStateOf(false) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bmp) { bmp.asImageBitmap() }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF2B2427)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("位置と大きさを合わせる", style = MaterialTheme.typography.titleMedium, color = Color.White)
                Text("1本指で動かす・2本指で拡大と縮小ができます", style = MaterialTheme.typography.bodySmall, color = Color(0xFFE6DADF))
                if (purpose.aspects.size > 1) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (a in purpose.aspects) ChoiceChip(a.label, aspect == a) { aspect = a; zoom = 1f; offset = Offset.Zero }
                    }
                }
                val ratio = aspect.ratio ?: (bmp.width.toFloat() / bmp.height)
                val frameW = if (area.width > 0) minOf(area.width.toFloat(), area.height * ratio) else 1f
                val frameH = frameW / ratio
                val base = maxOf(frameW / bmp.width, frameH / bmp.height)

                fun clamp(o: Offset, z: Float): Offset {
                    val dx = maxOf(0f, (bmp.width * base * z - frameW) / 2)
                    val dy = maxOf(0f, (bmp.height * base * z - frameH) / 2)
                    return Offset(o.x.coerceIn(-dx, dx), o.y.coerceIn(-dy, dy))
                }

                val scale = base * zoom
                val imgW = bmp.width * scale
                val imgH = bmp.height * scale
                val o = clamp(offset, zoom)
                val left = (frameW - imgW) / 2 + o.x
                val top = (frameH - imgH) / 2 + o.y

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .onSizeChanged { area = it },
                    contentAlignment = Alignment.Center,
                ) {
                    if (area.width > 0) {
                        Box(
                            Modifier
                                .size(with(density) { frameW.toDp() }, with(density) { frameH.toDp() })
                                .clipToBounds()
                                .pointerInput(bmp, frameW, frameH) {
                                    detectTransformGestures { _, pan, z, _ ->
                                        val nz = (zoom * z).coerceIn(1f, 6f)
                                        zoom = nz
                                        offset = clamp(offset + pan, nz)
                                    }
                                },
                        ) {
                            Canvas(Modifier.fillMaxSize()) {
                                drawImage(
                                    image,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(bmp.width, bmp.height),
                                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                                    dstSize = IntSize(imgW.roundToInt(), imgH.roundToInt()),
                                )
                                drawRect(Color.White.copy(alpha = 0.9f), style = Stroke(2.dp.toPx()))
                                if (purpose == PhotoPurpose.TOP) {
                                    // アイコンにするときに見える範囲
                                    drawCircle(Color.White.copy(alpha = 0.8f), radius = size.minDimension / 2, style = Stroke(1.5.dp.toPx()))
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("キャンセル", color = Color.White) }
                    OutlinedButton(onClick = { bmp = Images.rotate(bmp, 90); zoom = 1f; offset = Offset.Zero }) {
                        Text("↻ 回転", color = Color.White)
                    }
                    Box(Modifier.weight(1f))
                    Button(enabled = !busy && area.width > 0, onClick = {
                        busy = true
                        val src = bmp
                        val r = floatArrayOf(-left / scale, -top / scale, frameW / scale, frameH / scale)
                        scope.launch {
                            val bytes = withContext(Dispatchers.Default) {
                                val cut = Images.crop(src, r[0], r[1], r[2], r[3], purpose.maxSide)
                                Images.jpeg(cut, purpose.quality, purpose.maxBytes)
                            }
                            busy = false
                            onDone(bytes)
                        }
                    }) { Text("決定") }
                }
            }
        }
    }
}

/** 写真の小さな一覧。タップで大きく表示 */
@Composable
fun PhotoStrip(ids: List<String>, size: Int = 72, onOpen: (String) -> Unit) {
    if (ids.isEmpty()) return
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (id in ids) {
            PhotoImage(
                id,
                Modifier
                    .size(size.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onOpen(id) },
                maxSide = 400,
            )
        }
    }
}

/** 写真を画面いっぱいに表示する。2本指で拡大できる */
@Composable
fun PhotoViewer(id: String, title: String?, onDismiss: () -> Unit, onDelete: (() -> Unit)?) {
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFF1E191B)) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (onDelete != null) TextButton(onClick = { confirm = true }) { Text("削除", color = Color(0xFFFFB4C2)) }
                    TextButton(onClick = onDismiss) { Text("閉じる", color = Color.White) }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds()
                        .pointerInput(id) {
                            detectTransformGestures { _, p, z, _ ->
                                zoom = (zoom * z).coerceIn(1f, 6f)
                                pan = if (zoom == 1f) Offset.Zero else pan + p
                            }
                        },
                ) {
                    PhotoImage(
                        id,
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer(scaleX = zoom, scaleY = zoom, translationX = pan.x, translationY = pan.y),
                        contentScale = ContentScale.Fit,
                        maxSide = 2048,
                    )
                }
            }
        }
    }
    if (confirm && onDelete != null) {
        ConfirmDialog("この写真を削除します。", onDismiss = { confirm = false }, onConfirm = { confirm = false; onDelete() })
    }
}

/** トップ画像を丸く切り抜いたアイコン */
@Composable
fun PhotoAvatar(id: String, size: Int) {
    PhotoImage(
        id,
        Modifier
            .size(size.dp)
            .clip(CircleShape),
        maxSide = 256,
    )
}
