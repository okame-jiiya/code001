// 家族共有の記録を Firebase（Firestore）と同期する。データの形は Android アプリの FamilySync と同じ。
import { initializeApp } from 'https://www.gstatic.com/firebasejs/12.0.0/firebase-app.js';
import { getAuth, signInAnonymously, onAuthStateChanged } from 'https://www.gstatic.com/firebasejs/12.0.0/firebase-auth.js';
import {
  initializeFirestore, persistentLocalCache, persistentMultipleTabManager, doc, collection, onSnapshot,
  setDoc, deleteDoc, updateDoc, arrayUnion, serverTimestamp,
} from 'https://www.gstatic.com/firebasejs/12.0.0/firebase-firestore.js';
import { COLLECTIONS } from './model.js';

let db;
let auth;

export async function init() {
  const res = await fetch('./firebase-config.json', { cache: 'no-cache' });
  if (!res.ok) throw new Error('not-configured');
  const config = await res.json();
  const app = initializeApp(config);
  auth = getAuth(app);
  db = initializeFirestore(app, { localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }) });
}

async function signIn() {
  const user = await new Promise((resolve) => { const off = onAuthStateChanged(auth, (u) => { off(); resolve(u); }); });
  if (user) return user.uid;
  return (await signInAnonymously(auth)).user.uid;
}

/** 招待された家族に参加する（自分をメンバーに加える） */
export async function join(familyId) {
  const uid = await signIn();
  await updateDoc(doc(db, 'families', familyId), { members: arrayUnion(uid) });
}

/** 家族の記録を購読する。onChange(collection, Map<docId, 記録>) が変更のたびに呼ばれる */
export async function subscribe(familyId, onChange, onError) {
  await signIn();
  const unsubs = COLLECTIONS.map((name) =>
    onSnapshot(collection(db, 'families', familyId, name), (snap) => {
      const items = new Map();
      snap.forEach((d) => {
        try { items.set(d.id, JSON.parse(d.get('j'))); } catch { /* 壊れた記録は飛ばす */ }
      });
      onChange(name, items);
    }, (err) => onError(err.code === 'permission-denied' ? 'denied' : 'network')),
  );
  return () => unsubs.forEach((u) => u());
}

export function put(familyId, name, id, item) {
  return setDoc(doc(db, 'families', familyId, name, id), { j: JSON.stringify(item), u: serverTimestamp() });
}

export function remove(familyId, name, id) {
  return deleteDoc(doc(db, 'families', familyId, name, id));
}
