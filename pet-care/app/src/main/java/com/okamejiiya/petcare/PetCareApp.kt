package com.okamejiiya.petcare

import android.app.Application
import com.okamejiiya.petcare.data.FamilySync
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.notify.Reminders
import java.io.File

class PetCareApp : Application() {
    val store: PetStore by lazy { PetStore(File(filesDir, "petcare.json")) }
    val family: FamilySync by lazy { FamilySync(this, store) }

    override fun onCreate() {
        super.onCreate()
        Reminders.ensureChannel(this)
        store.onRemindersChanged = { Reminders.scheduleAll(this, it) }
        Reminders.scheduleAll(this, store.state.value)
        family.start()
    }
}
