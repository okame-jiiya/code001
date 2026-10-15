package com.okamejiiya.petcare.data

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.okamejiiya.petcare.BuildConfig
import com.okamejiiya.petcare.model.AppData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date

/**
 * 家族共有。Firebase（Google の無料サービス）の Firestore に記録を置き、家族の端末・Web 版と同期する。
 *
 * families/{familyId}                    … owner: 共有を始めた人
 * families/{familyId}/members/{uid}      … 参加している人（名前・端末の種類）。ここにいる人だけが読み書きできる
 * families/{familyId}/invites/{key}      … 招待（7日で期限切れ）。招待リンクに key が入る
 * families/{familyId}/{collection}/{id}  … 記録 1 件（j: JSON）
 * families/{familyId}/photos/{id}        … 写真 1 枚（b: 圧縮した JPEG の Base64）
 * users/{uid}                            … その人が入っている家族（Google ログインで別の端末から戻るとき用）
 *
 * ログインは最初は匿名。Google アカウントにつなぐと、機種変更や入れ直しのあとも同じ家族に戻れる。
 */
class FamilySync(context: Context, private val store: PetStore, private val photos: PhotoStore) {

    sealed interface Status {
        /** Firebase の設定がまだ入っていないビルド */
        data object NotConfigured : Status
        data object Off : Status
        data class Working(val message: String) : Status
        data class On(val familyId: String) : Status
        data class Error(val message: String, val familyId: String?) : Status

        /** Google ログインしたら、そのアカウントが入っている家族が見つかった */
        data class Found(val familyId: String) : Status
    }

    data class Member(val uid: String, val name: String, val platform: String, val google: Boolean, val joinedAt: Date?, val me: Boolean)
    data class Invite(val url: String, val expiresAt: Date)
    data class Account(val googleName: String?, val email: String?)

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("family", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val listeners = mutableListOf<ListenerRegistration>()

    private val _status = MutableStateFlow<Status>(if (configured) Status.Off else Status.NotConfigured)
    val status: StateFlow<Status> = _status
    private val _members = MutableStateFlow<List<Member>>(emptyList())
    val members: StateFlow<List<Member>> = _members
    private val _invite = MutableStateFlow<Invite?>(null)
    val invite: StateFlow<Invite?> = _invite
    private val _account = MutableStateFlow<Account?>(null)
    val account: StateFlow<Account?> = _account

    /** Google ログインの途中経過・失敗の理由（ボタンの下に出す） */
    data class GoogleMessage(val text: String, val error: Boolean, val offerFallback: Boolean = false)

    private val _googleMessage = MutableStateFlow<GoogleMessage?>(null)
    val googleMessage: StateFlow<GoogleMessage?> = _googleMessage

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
        ).also { AppCheckSetup.install(it) }
    }
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance(app) }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance(app) }

    private fun family(fid: String): DocumentReference = db.collection(FAMILIES).document(fid)
    private fun userDoc(uid: String): DocumentReference = db.collection(USERS).document(uid)

    /** アプリ起動時。共有中なら同期を再開する */
    fun start() {
        if (!configured) return
        photos.onAdded = { id, bytes -> familyId?.let { uploadPhoto(it, id, bytes) } }
        photos.onDeleted = { id ->
            familyId?.let { fid -> family(fid).collection(PHOTOS).document(id).delete().addOnFailureListener { Log.w(TAG, "写真を消せませんでした", it) } }
        }
        photos.fetcher = { id ->
            familyId?.let { fid ->
                family(fid).collection(PHOTOS).document(id).get().await().getString("b")
                    ?.let { Base64.decode(it, Base64.DEFAULT) }
            }
        }
        refreshAccount()
        val fid = familyId ?: return
        scope.launch {
            runCatching { signIn() }.onFailure { Log.w(TAG, "ログインできませんでした（オフラインかも）", it) }
            listen(fid)
        }
    }

    // ---------------- 共有を始める・参加する・やめる ----------------

    /** 共有を始める。この端末の記録をクラウドに送り、招待を作る */
    fun create() = launchWorking("共有の準備をしています…", "共有を始められませんでした。通信状態を確かめて、もう一度お試しください。") {
        val uid = signIn()
        val ref = db.collection(FAMILIES).document()
        db.batch()
            .set(ref, mapOf("owner" to uid, "createdAt" to FieldValue.serverTimestamp()))
            .set(ref.collection(MEMBERS).document(uid), memberData(null))
            .set(userDoc(uid), mapOf("familyId" to ref.id))
            .commit().await()
        prefs.edit().putString(KEY_FAMILY, ref.id).apply()
        val data = store.state.value
        send(ref.id, SyncData.diff(AppData(), data))
        for (id in photoIds(data)) photos.bytes(id)?.let { uploadPhoto(ref.id, id, it) }
        listen(ref.id)
        createInvite()
    }

    /** 招待された家族に参加する。この端末の記録は家族の記録に置き換わる */
    fun join(invite: InviteLink) {
        if (!configured) return
        scope.launch {
            _status.value = Status.Working("家族の記録を読み込んでいます…")
            try {
                val uid = signIn()
                addMember(invite.familyId, uid, invite.key)
                switchTo(invite.familyId)
            } catch (e: Exception) {
                Log.e(TAG, "参加できませんでした", e)
                _status.value = Status.Error(
                    if (e.isDenied()) "招待の期限が切れているか、取り消されています。家族に新しい招待 QR コードを出してもらってください。"
                    else "参加できませんでした。通信状態を確かめて、もう一度お試しください。",
                    familyId,
                )
            }
        }
    }

    /** Google ログインで見つかった家族の記録に切り替える（すでにメンバーなので招待はいらない） */
    fun switchToFound(fid: String) = launchWorking("家族の記録を読み込んでいます…", "家族の記録を読み込めませんでした。") {
        switchTo(fid)
    }

    fun dismissFound() {
        _status.value = familyId?.let { Status.On(it) } ?: Status.Off
    }

    /** 共有をやめる。この端末の記録はそのまま残る */
    fun leave() {
        val fid = familyId ?: return
        val uid = auth.currentUser?.uid
        stopListening()
        prefs.edit().remove(KEY_FAMILY).apply()
        _status.value = Status.Off
        _members.value = emptyList()
        _invite.value = null
        if (uid != null) {
            db.batch()
                .delete(family(fid).collection(MEMBERS).document(uid))
                .delete(userDoc(uid))
                .commit()
                .addOnFailureListener { Log.w(TAG, "家族のメンバーから外れられませんでした", it) }
        }
    }

    /** ほかのメンバーを家族から外す */
    fun removeMember(uid: String) {
        val fid = familyId ?: return
        family(fid).collection(MEMBERS).document(uid).delete()
            .addOnFailureListener { Log.w(TAG, "メンバーを外せませんでした", it) }
    }

    /** 新しい招待を作る（前の招待は取り消す）。7日で期限切れ */
    fun createInvite() {
        val fid = familyId ?: return
        scope.launch {
            try {
                val invites = family(fid).collection(INVITES)
                val old = invites.get().await().documents
                val expires = Date(System.currentTimeMillis() + INVITE_DAYS * 24 * 3600 * 1000L)
                db.batch().apply {
                    old.forEach { delete(it.reference) }
                    set(invites.document(), mapOf("expiresAt" to Timestamp(expires), "createdAt" to FieldValue.serverTimestamp()))
                }.commit().await()
            } catch (e: Exception) {
                Log.w(TAG, "招待を作れませんでした", e)
            }
        }
    }

    /** 招待をすべて取り消す（リンクが家族以外に渡ってしまったときなど） */
    fun revokeInvites() {
        val fid = familyId ?: return
        scope.launch {
            runCatching {
                val old = family(fid).collection(INVITES).get().await().documents
                db.batch().apply { old.forEach { delete(it.reference) } }.commit().await()
            }.onFailure { Log.w(TAG, "招待を取り消せませんでした", it) }
        }
    }

    // ---------------- Google ログイン ----------------

    /**
     * Google アカウントでログインする。今の匿名ログインを Google アカウントにつなぐので、家族はそのまま。
     * その Google アカウントが別の端末で使われていたときは、そちらでログインし直し、
     * 今いる家族があれば、その家族にも新しいアカウントで入り直す。
     */
    fun signInWithGoogle(activity: Activity, onFallback: () -> Unit) {
        if (!googleAvailable) return
        _googleMessage.value = GoogleMessage("Google のログイン画面を開いています…", false)
        scope.launch {
            val before = _status.value
            val idToken = try {
                val option = GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()
                val result = CredentialManager.create(activity)
                    .getCredential(activity, GetCredentialRequest.Builder().addCredentialOption(option).build())
                val cred = result.credential
                if (cred !is CustomCredential || cred.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    error("Google 以外のログイン情報が返されました")
                }
                GoogleIdTokenCredential.createFrom(cred.data).idToken
            } catch (e: GetCredentialCancellationException) {
                // 自分で閉じたときのほか、設定の問題（[16] Account reauth failed など）でもここに来る
                Log.w(TAG, "Google ログインが閉じられました", e)
                _status.value = before
                _googleMessage.value = GoogleMessage(
                    "ログインが完了しませんでした。アカウントを選んでもこうなる場合は「別の方法でログイン」をお試しください。" +
                        (e.message?.takeIf { it.isNotBlank() }?.let { "\n（$it）" } ?: ""),
                    true,
                    offerFallback = true,
                )
                return@launch
            } catch (e: Exception) {
                // Credential Manager が使えない端末・設定のときは、従来の Google ログイン画面で試す
                Log.w(TAG, "Credential Manager でログインできませんでした。従来の方法で試します", e)
                _status.value = before
                _googleMessage.value = null
                onFallback()
                return@launch
            }
            finishGoogleSignIn(idToken, before)
        }
    }

    /** 従来の Google ログイン画面（GoogleSignIn）で取った ID トークンでログインする */
    fun signInWithGoogleIdToken(idToken: String) {
        scope.launch { finishGoogleSignIn(idToken, _status.value) }
    }

    /** 従来の Google ログイン画面が失敗したとき */
    fun googleFailed(code: Int, message: String?) {
        if (code == 12501) { // 自分で閉じた
            _googleMessage.value = null
            return
        }
        val hint = when (code) {
            10 -> "アプリの登録情報が Google 側と合っていません（エラー10）。Google Cloud の「認証情報」に、" +
                "パッケージ名 ${BuildConfig.APPLICATION_ID} とこのアプリの SHA-1 の Android 用 OAuth クライアントが必要です。"
            7 -> "インターネットにつながっていないようです（エラー7）。"
            else -> "Google アカウントでログインできませんでした（エラー$code）。"
        }
        _googleMessage.value = GoogleMessage(hint + (message?.takeIf { it.isNotBlank() }?.let { "\n（$it）" } ?: ""), true)
    }

    fun dismissGoogleMessage() {
        _googleMessage.value = null
    }

    private suspend fun finishGoogleSignIn(idToken: String, before: Status) {
        try {
            val googleCred = GoogleAuthProvider.getCredential(idToken, null)
            _googleMessage.value = null
            _status.value = Status.Working("Google アカウントでログインしています…")

            val current = auth.currentUser
            val fid = familyId
            if (current == null) {
                auth.signInWithCredential(googleCred).await()
            } else {
                try {
                    current.linkWithCredential(googleCred).await()
                } catch (e: FirebaseAuthUserCollisionException) {
                    // この Google アカウントは前に別の端末（またはこの端末の前のインストール）で使われていた
                    val bridge = fid?.let { newInviteKey(it) }
                    val oldUid = current.uid
                    auth.signInWithCredential(googleCred).await()
                    val uid = requireNotNull(auth.currentUser).uid
                    if (fid != null && bridge != null) {
                        val already = family(fid).collection(MEMBERS).document(uid).get().await().exists()
                        if (!already) addMember(fid, uid, bridge)
                        runCatching { family(fid).collection(MEMBERS).document(oldUid).delete().await() }
                        runCatching { family(fid).collection(INVITES).document(bridge).delete().await() }
                    }
                }
            }
            refreshAccount()
            val uid = requireNotNull(auth.currentUser).uid
            val fidNow = familyId
            if (fidNow != null) {
                // 名前を Google の名前にし、別の端末からも戻れるよう家族を覚えておく
                family(fidNow).collection(MEMBERS).document(uid).update(mapOf("name" to displayName(), "google" to true)).await()
                userDoc(uid).set(mapOf("familyId" to fidNow)).await()
                listen(fidNow)
            } else {
                val found = userDoc(uid).get().await().getString("familyId")
                _status.value = if (found != null) Status.Found(found) else Status.Off
            }
        } catch (e: Exception) {
            Log.e(TAG, "Google ログインできませんでした", e)
            _status.value = before
            _googleMessage.value = GoogleMessage(
                "Google アカウントでログインできませんでした。" + (e.message?.takeIf { it.isNotBlank() }?.let { "\n（$it）" } ?: ""),
                true,
            )
        }
    }

    private fun refreshAccount() {
        val user = auth.currentUser
        val google = user?.providerData?.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        _account.value = Account(google?.displayName ?: user?.displayName.takeIf { google != null }, google?.email)
    }

    // ---------------- 内部 ----------------

    private suspend fun signIn(): String =
        auth.currentUser?.uid ?: requireNotNull(auth.signInAnonymously().await().user).uid.also { refreshAccount() }

    private fun displayName(): String {
        val user = auth.currentUser
        val google = user?.providerData?.firstOrNull { it.providerId == GoogleAuthProvider.PROVIDER_ID }
        return google?.displayName?.takeIf { it.isNotBlank() } ?: "Android（${Build.MODEL}）"
    }

    private fun memberData(inviteKey: String?): Map<String, Any> = buildMap {
        put("name", displayName())
        put("platform", "android")
        put("google", _account.value?.email != null)
        put("joinedAt", FieldValue.serverTimestamp())
        if (inviteKey != null) put("invite", inviteKey)
    }

    private suspend fun addMember(fid: String, uid: String, inviteKey: String) {
        db.batch()
            .set(family(fid).collection(MEMBERS).document(uid), memberData(inviteKey))
            .set(userDoc(uid), mapOf("familyId" to fid))
            .commit().await()
    }

    /** 自分で使うだけの短い招待（Google ログインで別アカウントに切り替わるとき、家族に入り直す用） */
    private suspend fun newInviteKey(fid: String): String? = runCatching {
        val doc = family(fid).collection(INVITES).document()
        doc.set(mapOf("expiresAt" to Timestamp(Date(System.currentTimeMillis() + 10 * 60 * 1000L)), "createdAt" to FieldValue.serverTimestamp())).await()
        doc.id
    }.getOrNull()

    private fun switchTo(fid: String) {
        stopListening()
        prefs.edit().putString(KEY_FAMILY, fid).apply()
        store.applyRemote { SyncData.clearShared(it) }
        listen(fid)
    }

    private fun launchWorking(message: String, failure: String, block: suspend () -> Unit) {
        if (!configured) return
        scope.launch {
            _status.value = Status.Working(message)
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, failure, e)
                _status.value = Status.Error(failure, familyId)
            }
        }
    }

    private fun listen(fid: String) {
        stopListening()
        // この端末での変更をクラウドへ送る
        store.onLocalChange = { prev, next -> send(fid, SyncData.diff(prev, next)) }
        val ref = family(fid)
        _status.value = Status.On(fid)
        for (c in SyncData.collections) {
            listeners += ref.collection(c.name).addSnapshotListener { snap, err ->
                if (err != null) {
                    Log.w(TAG, "${c.name} を同期できませんでした", err)
                    // 新しく増えた記録の種類は、ルールの更新前だと読めないだけなので、共有全体は止めない
                    if (err.isDenied() && c.name == "pets") {
                        _status.value = Status.Error("家族の記録を読めませんでした。家族から外された可能性があります。共有をやめて、招待から参加し直してください。", fid)
                    }
                    return@addSnapshotListener
                }
                if (snap == null) return@addSnapshotListener
                val docs = snap.documents.mapNotNull { d -> d.getString("j")?.let { d.id to it } }.toMap()
                store.applyRemote { c.applyDocs(it, docs) }
            }
        }
        listeners += ref.collection(MEMBERS).addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            val me = auth.currentUser?.uid
            _members.value = snap.documents.map { d ->
                Member(
                    uid = d.id,
                    name = d.getString("name").orEmpty().ifBlank { "メンバー" },
                    platform = d.getString("platform").orEmpty(),
                    google = d.getBoolean("google") == true,
                    joinedAt = d.getTimestamp("joinedAt")?.toDate(),
                    me = d.id == me,
                )
            }.sortedBy { it.joinedAt ?: Date(Long.MAX_VALUE) }
            if (!snap.metadata.isFromCache && me != null && snap.documents.none { it.id == me }) {
                stopListening()
                _status.value = Status.Error("この端末は家族の共有から外されました。続けるには、家族に招待してもらってください。", fid)
            }
        }
        listeners += ref.collection(INVITES).addSnapshotListener { snap, err ->
            if (err != null || snap == null) return@addSnapshotListener
            val now = Date()
            _invite.value = snap.documents
                .mapNotNull { d -> d.getTimestamp("expiresAt")?.toDate()?.takeIf { it.after(now) }?.let { d.id to it } }
                // 10 分だけの内部用の招待は出さない
                .filter { (_, exp) -> exp.time - now.time > 30 * 60 * 1000L }
                .maxByOrNull { it.second }
                ?.let { (key, exp) -> Invite(inviteUrl(fid, key), exp) }
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
        val ref = family(fid)
        for (chunk in changes.chunked(400)) {
            val batch = db.batch()
            for (ch in chunk) {
                val doc = ref.collection(ch.collection).document(ch.id)
                if (ch.json == null) batch.delete(doc)
                else batch.set(doc, mapOf("j" to ch.json, "u" to FieldValue.serverTimestamp()))
            }
            batch.commit().addOnFailureListener { Log.e(TAG, "記録を送れませんでした", it) }
        }
    }

    private fun uploadPhoto(fid: String, id: String, bytes: ByteArray) {
        family(fid).collection(PHOTOS).document(id)
            .set(mapOf("b" to Base64.encodeToString(bytes, Base64.NO_WRAP), "u" to FieldValue.serverTimestamp()))
            .addOnFailureListener { Log.e(TAG, "写真を送れませんでした", it) }
    }

    private fun photoIds(d: AppData): Set<String> =
        (d.pets.flatMap { listOfNotNull(it.photoId) + it.documents.map { doc -> doc.photoId } } + d.excretionLogs.flatMap { it.photoIds }).toSet()

    private fun Exception.isDenied() =
        this is FirebaseFirestoreException && code == FirebaseFirestoreException.Code.PERMISSION_DENIED

    /** 招待リンクの中身 */
    data class InviteLink(val familyId: String, val key: String)

    companion object {
        private const val TAG = "FamilySync"
        private const val APP_NAME = "family"
        private const val FAMILIES = "families"
        private const val MEMBERS = "members"
        private const val INVITES = "invites"
        private const val USERS = "users"
        private const val PHOTOS = "photos"
        private const val KEY_FAMILY = "familyId"
        private const val INVITE_DAYS = 7

        val configured: Boolean
            get() = BuildConfig.FIREBASE_API_KEY.isNotBlank() && BuildConfig.FIREBASE_PROJECT_ID.isNotBlank() &&
                BuildConfig.FIREBASE_APP_ID.isNotBlank()

        val googleAvailable: Boolean get() = configured && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank()

        fun inviteUrl(fid: String, key: String): String = "${BuildConfig.WEB_URL}#join=$fid.$key"

        private val INVITE = Regex("""(?:join=|[?&]f=)([A-Za-z0-9]{12,40})[.]([A-Za-z0-9]{12,40})""")
        private val APP_INVITE = Regex("""[?&]f=([A-Za-z0-9]{12,40})&k=([A-Za-z0-9]{12,40})""")

        /** 招待リンク（https://…#join=家族.招待 や petcare://join?f=家族&k=招待）を読み取る */
        fun parseInvite(text: String): InviteLink? =
            (INVITE.find(text) ?: APP_INVITE.find(text))?.let { InviteLink(it.groupValues[1], it.groupValues[2]) }
    }
}
