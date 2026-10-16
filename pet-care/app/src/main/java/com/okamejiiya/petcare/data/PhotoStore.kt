package com.okamejiiya.petcare.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.okamejiiya.petcare.model.Ids
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 写真（圧縮した JPEG）を 1 枚 1 ファイルで保存する。記録の JSON には写真の ID だけを入れる。
 * 家族共有中は、家族の端末で撮った写真を必要になったときにクラウドから取ってくる。
 */
class PhotoStore(private val dir: File) {
    private val _version = MutableStateFlow(0)

    /** 写真が増えたり消えたりすると変わる（画面の再読み込み用） */
    val version: StateFlow<Int> = _version

    /** この端末で写真を追加したとき（家族共有でクラウドへ送る用） */
    @Volatile
    var onAdded: ((id: String, bytes: ByteArray) -> Unit)? = null

    /** この端末で写真を消したとき */
    @Volatile
    var onDeleted: ((id: String) -> Unit)? = null

    /** 端末に無い写真をクラウドから取ってくる */
    @Volatile
    var fetcher: (suspend (id: String) -> ByteArray?)? = null

    private fun file(id: String): File? = if (VALID.matches(id)) File(dir, "$id.jpg") else null

    fun has(id: String): Boolean = file(id)?.exists() == true

    fun bytes(id: String): ByteArray? = file(id)?.takeIf { it.exists() }?.readBytes()

    fun add(bytes: ByteArray): String {
        val id = Ids.next().toString()
        write(id, bytes)
        onAdded?.invoke(id, bytes)
        return id
    }

    /** 家族の端末から届いた写真を保存する（クラウドへは送り返さない） */
    fun putRemote(id: String, bytes: ByteArray) = write(id, bytes)

    fun delete(id: String) {
        file(id)?.delete()
        onDeleted?.invoke(id)
        _version.value++
    }

    private fun write(id: String, bytes: ByteArray) {
        val f = file(id) ?: return
        dir.mkdirs()
        val tmp = File(dir, "$id.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(f)
        _version.value++
    }

    /** 写真を読み込む。端末に無ければクラウドから取ってくる */
    suspend fun load(id: String, maxSide: Int = 2048): Bitmap? = withContext(Dispatchers.IO) {
        if (!has(id)) {
            val got = runCatching { fetcher?.invoke(id) }.onFailure { Log.w(TAG, "写真を取ってこられませんでした", it) }.getOrNull()
            if (got != null) write(id, got)
        }
        val f = file(id)?.takeIf { it.exists() } ?: return@withContext null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private companion object {
        const val TAG = "PhotoStore"
        val VALID = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
