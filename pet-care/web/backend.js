// 家族共有の記録を Firebase（Firestore）と同期する。データの形とルールは Android アプリの FamilySync と同じ。
import { initializeApp } from 'https://www.gstatic.com/firebasejs/12.0.0/firebase-app.js';
import {
  getAuth, signInAnonymously, onAuthStateChanged, GoogleAuthProvider, linkWithPopup, signInWithPopup, signInWithCredential,
} from 'https://www.gstatic.com/firebasejs/12.0.0/firebase-auth.js';
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager, doc, collection, onSnapshot, getDoc, getDocs,
  setDoc, deleteDoc, updateDoc, writeBatch, serverTimestamp, Timestamp,
} from 'https://www.gstatic.com/firebasejs/12.0.0/firebase-firestore.js';
import { initializeAppCheck, ReCaptchaEnterpriseProvider } from 'https://www.gstatic.com/firebasejs/12.0.0/firebase-app-check.js';
import { COLLECTIONS } from './model.js';

const INVITE_DAYS = 7;
let db;
let auth;
let ready;

export async function init() {
  const res = await fetch('./firebase-config.json', { cache: 'no-cache' });
  if (!res.ok) throw new Error('not-configured');
  const config = await res.json();
  const app = initializeApp(config);
  // 不正利用対策：reCAPTCHA Enterprise のサイトキーが設定されていれば App Check を使う
  if (config.recaptchaSiteKey) {
    initializeAppCheck(app, { provider: new ReCaptchaEnterpriseProvider(config.recaptchaSiteKey), isTokenAutoRefreshEnabled: true });
  }
  auth = getAuth(app);
  db = initializeFirestore(app, { localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }) });
  ready = new Promise((resolve) => { const off = onAuthStateChanged(auth, (u) => { off(); resolve(u); }); });
  await ready;
  return { googleAvailable: true };
}

async function signIn() {
  await ready;
  if (auth.currentUser) return auth.currentUser.uid;
  return (await signInAnonymously(auth)).user.uid;
}

const googleInfo = () => auth.currentUser?.providerData.find((p) => p.providerId === 'google.com') || null;

export function account() {
  const g = googleInfo();
  return g ? { google: true, name: g.displayName || '', email: g.email || '' } : { google: false };
}

function deviceName() {
  const g = googleInfo();
  if (g?.displayName) return g.displayName;
  const ua = navigator.userAgent;
  const kind = /iPhone/.test(ua) ? 'iPhone' : /iPad|Macintosh.*Mobile/.test(ua) ? 'iPad' : /Android/.test(ua) ? 'Android' : 'パソコン';
  return `Web（${kind}）`;
}

const memberRef = (fid, uid) => doc(db, 'families', fid, 'members', uid);
const userRef = (uid) => doc(db, 'users', uid);

function memberData(inviteKey) {
  const d = { name: deviceName(), platform: 'web', google: !!googleInfo(), joinedAt: serverTimestamp() };
  if (inviteKey) d.invite = inviteKey;
  return d;
}

async function addMember(fid, uid, inviteKey) {
  const b = writeBatch(db);
  b.set(memberRef(fid, uid), memberData(inviteKey));
  b.set(userRef(uid), { familyId: fid });
  await b.commit();
}

/** 招待リンクで家族に参加する */
export async function join(fid, key) {
  const uid = await signIn();
  await addMember(fid, uid, key);
}

/**
 * Google アカウントでログインする。今の匿名ログインを Google につなぐので家族はそのまま。
 * 別の端末で使っていた Google アカウントなら、そちらに切り替えて家族に入り直す。
 * 戻り値：ログイン後に入っている（または見つかった）家族 ID
 */
export async function signInWithGoogle(fid) {
  const provider = new GoogleAuthProvider();
  await ready;
  const current = auth.currentUser;
  if (!current) {
    await signInWithPopup(auth, provider);
  } else {
    try {
      await linkWithPopup(current, provider);
    } catch (e) {
      if (e.code !== 'auth/credential-already-in-use') throw e;
      const cred = GoogleAuthProvider.credentialFromError(e);
      const bridge = fid ? await newInviteKey(fid, 10 * 60 * 1000).catch(() => null) : null;
      const oldUid = current.uid;
      await signInWithCredential(auth, cred);
      const uid = auth.currentUser.uid;
      if (fid && bridge) {
        if (!(await getDoc(memberRef(fid, uid))).exists()) await addMember(fid, uid, bridge);
        await deleteDoc(memberRef(fid, oldUid)).catch(() => {});
        await deleteDoc(doc(db, 'families', fid, 'invites', bridge)).catch(() => {});
      }
    }
  }
  const uid = auth.currentUser.uid;
  if (fid) {
    await updateDoc(memberRef(fid, uid), { name: deviceName(), google: true });
    await setDoc(userRef(uid), { familyId: fid });
    return fid;
  }
  return (await getDoc(userRef(uid))).get('familyId') || null;
}

async function newInviteKey(fid, ms) {
  const ref = doc(collection(db, 'families', fid, 'invites'));
  await setDoc(ref, { expiresAt: Timestamp.fromMillis(Date.now() + ms), createdAt: serverTimestamp() });
  return ref.id;
}

/** 新しい招待を作る（前の招待は取り消す） */
export async function createInvite(fid) {
  const old = await getDocs(collection(db, 'families', fid, 'invites'));
  const b = writeBatch(db);
  old.forEach((d) => b.delete(d.ref));
  b.set(doc(collection(db, 'families', fid, 'invites')), {
    expiresAt: Timestamp.fromMillis(Date.now() + INVITE_DAYS * 86400000), createdAt: serverTimestamp(),
  });
  await b.commit();
}

export async function revokeInvites(fid) {
  const old = await getDocs(collection(db, 'families', fid, 'invites'));
  const b = writeBatch(db);
  old.forEach((d) => b.delete(d.ref));
  await b.commit();
}

export const removeMember = (fid, uid) => deleteDoc(memberRef(fid, uid));

export async function leave(fid) {
  const uid = auth.currentUser?.uid;
  if (!uid) return;
  const b = writeBatch(db);
  b.delete(memberRef(fid, uid));
  b.delete(userRef(uid));
  await b.commit();
}

/**
 * 家族の記録・メンバー・招待を購読する。
 * on.data(collection, Map) / on.members([...]) / on.invite({key, expiresAt}|null) / on.error('denied'|'network'|'kicked')
 */
export async function subscribe(fid, on) {
  const me = await signIn();
  const fail = (err) => on.error(err.code === 'permission-denied' ? 'denied' : 'network');
  const unsubs = COLLECTIONS.map((name) =>
    onSnapshot(collection(db, 'families', fid, name), (snap) => {
      const items = new Map();
      snap.forEach((d) => {
        try { items.set(d.id, JSON.parse(d.get('j'))); } catch { /* 壊れた記録は飛ばす */ }
      });
      on.data(name, items);
    }, fail));
  unsubs.push(onSnapshot(collection(db, 'families', fid, 'members'), (snap) => {
    const uid = auth.currentUser?.uid || me;
    const list = snap.docs.map((d) => ({
      uid: d.id, name: d.get('name') || 'メンバー', platform: d.get('platform') || '', google: !!d.get('google'),
      joinedAt: d.get('joinedAt')?.toMillis?.() ?? null, me: d.id === uid,
    })).sort((a, b) => (a.joinedAt ?? Infinity) - (b.joinedAt ?? Infinity));
    on.members(list);
    if (!snap.metadata.fromCache && !snap.docs.some((d) => d.id === uid)) on.error('kicked');
  }, () => {}));
  unsubs.push(onSnapshot(collection(db, 'families', fid, 'invites'), (snap) => {
    const now = Date.now();
    const best = snap.docs
      .map((d) => ({ key: d.id, expiresAt: d.get('expiresAt')?.toMillis?.() ?? 0 }))
      .filter((i) => i.expiresAt - now > 30 * 60 * 1000)
      .sort((a, b) => b.expiresAt - a.expiresAt)[0];
    on.invite(best || null);
  }, () => {}));
  return () => unsubs.forEach((u) => u());
}

export function put(fid, name, id, item) {
  return setDoc(doc(db, 'families', fid, name, id), { j: JSON.stringify(item), u: serverTimestamp() });
}

export function remove(fid, name, id) {
  return deleteDoc(doc(db, 'families', fid, name, id));
}
