package com.okamejiiya.petcare

import android.app.Application
import com.okamejiiya.petcare.data.FamilySync
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.data.PhotoStore
import com.okamejiiya.petcare.data.UiPrefs
import com.okamejiiya.petcare.notify.Reminders
import java.io.File

class PetCareApp : Application() {
    val store: PetStore by lazy { PetStore(File(filesDir, "petcare.json")) }
    val photos: PhotoStore by lazy { PhotoStore(File(filesDir, "photos")) }
    val prefs: UiPrefs by lazy { UiPrefs(this) }
    val family: FamilySync by lazy { FamilySync(this, store, photos) }

    override fun onCreate() {
        super.onCreate()
        Reminders.ensureChannel(this)
        store.onRemindersChanged = { Reminders.scheduleAll(this, it) }
        Reminders.scheduleAll(this, store.state.value)
        family.start()
    }
}
