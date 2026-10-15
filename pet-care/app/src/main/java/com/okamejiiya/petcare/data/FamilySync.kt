package com.okamejiiya.petcare.data

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.okamejiiya.petcare.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * 家族共有。Firebase（Google の無料サービス）の Firestore に記録を置き、家族の端末・Web 版と同期する。
 *
 * families/{familyId}            … members: 参加している端末（匿名ログインの uid）
 * families/{familyId}/{collection}/{docId} … j: 記録 1 件の JSON
 *
 * familyId は推測できない長いランダム文字列で、招待リンク（QR コード）にだけ載せる。
 */
class FamilySync(context: Context, private val store: PetStore) {

    sealed interface Status {
        /** Firebase の設定がまだ入っていないビルド */
        data object NotConfigured : Status
        data object Off : Status
        data class Working(val message: String) : Status
        data class On(val familyId: String) : Status
        data class Error(val message: String, val familyId: String?) : Status
    }

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("family", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = mutableListOf<ListenerRegistration>()

    private val _status = MutableStateFlow<Status>(if (configured) Status.Off else Status.NotConfigured)
    val status: StateFlow<Status> = _status

    val familyId: String? get() = prefs.getString(KEY_FAMILY, null)

    private val app: FirebaseApp by lazy {
        FirebaseApp.getApps(appContext).firstOrNull { it.name == APP_NAME } ?: FirebaseApp.initializeApp(
            appContext,
            FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                .build(),
            APP_NAME,
        )
    }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance(app) }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance(app) }

    /** アプリ起動時。共有中なら同期を再開する */
    fun start() {
        val fid = familyId ?: return
        if (!configured) return
        scope.launch {
            runCatching { signIn() }.onFailure { Log.w(TAG, "ログインできませんでした（オフラインかも）", it) }
            listen(fid)
        }
    }

    /** 共有を始める。この端末の記録をクラウドに送り、招待リンクを作る */
    fun create() {
        if (!configured) return
        scope.launch {
            _status.value = Status.Working("共有の準備をしています…")
            try {
                val uid = signIn()
                val ref = db.collection(FAMILIES).document()
                ref.set(mapOf("members" to listOf(uid), "createdAt" to FieldValue.serverTimestamp())).await()
                prefs.edit().putString(KEY_FAMILY, ref.id).apply()
                send(ref.id, SyncData.diff(com.okamejiiya.petcare.model.AppData(), store.state.value))
                listen(ref.id)
            } catch (e: Exception) {
                Log.e(TAG, "共有を始められませんでした", e)
                _status.value = Status.Error("共有を始められませんでした。通信状態を確かめて、もう一度お試しください。", null)
            }
        }
    }

    /** 招待された家族に参加する。この端末の記録は家族の記録に置き換わる */
    fun join(fid: String) {
        if (!configured) return
        scope.launch {
            _status.value = Status.Working("家族の記録を読み込んでいます…")
            try {
                val uid = signIn()
                db.collection(FAMILIES).document(fid).update("members", FieldValue.arrayUnion(uid)).await()
                stopListening()
                prefs.edit().putString(KEY_FAMILY, fid).apply()
                store.applyRemote { SyncData.clearShared(it) }
                listen(fid)
            } catch (e: Exception) {
                Log.e(TAG, "参加できませんでした", e)
                val msg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    "招待リンクが見つかりませんでした。リンクをもう一度確かめてください。"
                } else {
                    "参加できませんでした。通信状態を確かめて、もう一度お試しください。"
                }
                _status.value = Status.Error(msg, familyId)
            }
        }
    }

    /** 共有をやめる。この端末の記録はそのまま残る */
    fun leave() {
        val fid = familyId ?: return
        stopListening()
        prefs.edit().remove(KEY_FAMILY).apply()
        _status.value = Status.Off
        val uid = auth.currentUser?.uid ?: return
        db.collection(FAMILIES).document(fid).update("members", FieldValue.arrayRemove(uid))
            .addOnFailureListener { Log.w(TAG, "家族のメンバーから外れられませんでした", it) }
    }

    fun inviteUrl(fid: String): String = "${BuildConfig.WEB_URL}#join=$fid"

    private suspend fun signIn(): String =
        auth.currentUser?.uid ?: requireNotNull(auth.signInAnonymously().await().user).uid

    private fun listen(fid: String) {
        stopListening()
        // この端末での変更をクラウドへ送る
        store.onLocalChange = { prev, next -> send(fid, SyncData.diff(prev, next)) }
        val family = db.collection(FAMILIES).document(fid)
        _status.value = Status.On(fid)
        for (c in SyncData.collections) {
            listeners += family.collection(c.name).addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "${c.name} を同期できませんでした", err)
                    if (err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        _status.value = Status.Error("家族の記録を読めませんでした。共有をやめて、招待リンクから参加し直してください。", fid)
                    }
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val docs = snap.documents.mapNotNull { d -> d.getString("j")?.let { d.id to it } }.toMap()
                store.applyRemote { c.applyDocs(it, docs) }
            }
        }
    }

    private fun stopListening() {
        store.onLocalChange = null
        listeners.forEach { it.remove() }
        listeners.clear()
    }

    /** Firestore は 1 回のまとめ書きが 500 件までなので分けて送る。オフライン中は端末にためて、つながったら送られる */
    private fun send(fid: String, changes: List<DocChange>) {
        if (changes.isEmpty()) return
        val family = db.collection(FAMILIES).document(fid)
        for (chunk in changes.chunked(400)) {
            val batch = db.batch()
            for (ch in chunk) {
                val doc = family.collection(ch.collection).document(ch.id)
                if (ch.json == null) batch.delete(doc)
                else batch.set(doc, mapOf("j" to ch.json, "u" to FieldValue.serverTimestamp()))
            }
            batch.commit().addOnFailureListener { Log.e(TAG, "記録を送れませんでした", it) }
        }
    }

    companion object {
        private const val TAG = "FamilySync"
        private const val APP_NAME = "family"
        private const val FAMILIES = "families"
        private const val KEY_FAMILY = "familyId"

        val configured: Boolean
            get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
                BuildConfig.FIREBASE_APP_ID.isNotBlank()

        private val INVITE = Regex("""(?:join=|[?&]f=)([A-Za-z0-9]{12,40})""")

        /** 招待リンク（https://…#join=xxx や petcare://join?f=xxx）から家族 ID を取り出す */
        fun parseInvite(text: String): String? = INVITE.find(text)?.groupValues?.get(1)
    }
}
