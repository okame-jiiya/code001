package com.okamejiiya.petcare

import android.app.Application
import com.okamejiiya.petcare.data.PetStore
import java.io.File

class PetCareApp : Application() {
    val store: PetStore by lazy { PetStore(File(filesDir, "petcare.json")) }
}
