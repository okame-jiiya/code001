package com.okamejiiya.petcare.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.okamejiiya.petcare.data.FamilySync
import com.okamejiiya.petcare.data.PetStore
import com.okamejiiya.petcare.data.PhotoStore
import com.okamejiiya.petcare.data.Screen
import com.okamejiiya.petcare.data.Section
import com.okamejiiya.petcare.data.UiPrefs
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Pet

enum class Tab(val label: String, val emoji: String, val section: Section) {
    HOME("きょう", "🏠", Section.TAB_HOME),
    CALENDAR("カレンダー", "📅", Section.TAB_CALENDAR),
    FOOD("ごはん", "🍚", Section.TAB_FOOD),
    PET("うちの子", "🐾", Section.TAB_PET),
    SETTINGS("設定", "⚙", Section.TAB_SETTINGS),
}

/** この端末の表示の設定（どの画面からでも使えるように） */
val LocalPrefs = staticCompositionLocalOf<UiPrefs> { error("UiPrefs がありません") }

/** 写真の読み込み先（どの画面からでも使えるように） */
val LocalPhotos = staticCompositionLocalOf<PhotoStore> { error("PhotoStore がありません") }

@Composable
fun PetCareRoot(
    store: PetStore,
    family: FamilySync,
    photos: PhotoStore,
    prefs: UiPrefs,
    invite: FamilySync.InviteLink?,
    onInviteHandled: () -> Unit,
) {
    val fontScale by prefs.fontScale.collectAsState()
    PetCareTheme(fontScale) {
      CompositionLocalProvider(LocalPhotos provides photos, LocalPrefs provides prefs) {
        val data by store.state.collectAsState()
        val layout by prefs.layout.collectAsState()
        val hidden by prefs.hidden.collectAsState()
        val tabs = layout.getValue(Screen.TABS).filter { it !in hidden }.mapNotNull { s -> Tab.entries.firstOrNull { it.section == s } }
        var tabState by rememberSaveable { mutableStateOf(tabs.first()) }
        val tab = if (tabState in tabs) tabState else tabs.first()
        var addingPet by rememberSaveable { mutableStateOf(false) }
        var editingPetId by rememberSaveable { mutableStateOf<Long?>(null) }
        var askLink by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val pet = data.selectedPet
        val familyStatus by family.status.collectAsState()
        val firstTimeCard: @Composable () -> Unit = { FirstTimeFamily(family, familyStatus, onInviteLink = { askLink = true }) }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val editingPet = editingPetId?.let { id -> data.pets.firstOrNull { it.id == id } }
            if (editingPet != null) {
                BackHandler { editingPetId = null }
                PetEditor(
                    initial = editingPet,
                    firstTime = false,
                    onSave = { store.upsertPet(it); editingPetId = null },
                    onCancel = { editingPetId = null },
                    onDelete = { store.deletePet(editingPet.id); editingPetId = null },
                    modifier = Modifier.systemBarsPadding(),
                )
            } else if (pet == null || addingPet) {
                if (addingPet) BackHandler { addingPet = false }
                PetEditor(
                    initial = null,
                    firstTime = pet == null,
                    defaultColor = nextFreeColor(data.pets),
                    onSave = {
                        store.upsertPet(it)
                        store.selectPet(it.id)
                        addingPet = false
                    },
                    onCancel = if (pet == null) null else ({ addingPet = false }),
                    onDelete = null,
                    modifier = Modifier.systemBarsPadding(),
                    familyCard = if (pet == null && FamilySync.configured) firstTimeCard else null,
                )
            } else {
            Scaffold(
                // 「うちの子」と「設定」は全員を扱う画面なので、上のペット切り替えは出さない
                topBar = { PetSwitcher(data, pet, showPets = tab != Tab.PET && tab != Tab.SETTINGS, onSelect = { store.selectPet(it) }) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        for (t in tabs) {
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { tabState = t },
                                icon = { Text(t.emoji, style = MaterialTheme.typography.titleLarge) },
                                label = { Text(t.label) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            )
                        }
                    }
                },
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background,
            ) { padding ->
                val m = Modifier.padding(padding)
                when (tab) {
                    Tab.HOME -> HomeScreen(data, pet, store, snackbar, m, onGoFood = { tabState = Tab.FOOD })
                    Tab.FOOD -> FoodScreen(data, pet, store, m)
                    Tab.CALENDAR -> CalendarScreen(data, pet, store, prefs, snackbar, m)
                    Tab.PET -> PetScreen(data, pet, store, m, onEdit = { editingPetId = it })
                    Tab.SETTINGS -> SettingsScreen(data, pet, store, family, prefs, m, onAddPet = { addingPet = true }, onEditPet = { editingPetId = it })
                }
            }
            }
        }

        // 招待リンクから開かれたとき・最初の画面で「招待リンクで参加」を選んだとき
        if (askLink) InviteLinkDialog(onDismiss = { askLink = false }) { askLink = false; family.join(it); tabState = Tab.HOME }
        if (invite != null) {
            if (FamilySync.configured) {
                JoinFamilyDialog(onDismiss = onInviteHandled) { onInviteHandled(); family.join(invite); tabState = Tab.HOME }
            } else LaunchedEffect(invite) { onInviteHandled() }
        }
        (familyStatus as? FamilySync.Status.Found)?.let { found ->
            FoundFamilyDialog(onDismiss = { family.dismissFound() }) { family.switchToFound(found.familyId); tabState = Tab.HOME }
        }
      }
    }
}

@Composable
private fun PetSwitcher(data: AppData, pet: Pet, showPets: Boolean, onSelect: (Long) -> Unit) {
    Column(
        Modifier
            .statusBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    ) {
        Text(
            "うちの子ごはん帳",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
        )
        if (showPets) Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in data.sortedPets) {
                FilterChip(
                    selected = p.id == pet.id,
                    onClick = { onSelect(p.id) },
                    label = { Text(p.name, fontWeight = FontWeight.Bold) },
                    leadingIcon = { PetAvatar(p, 24) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
        }
    }
}
