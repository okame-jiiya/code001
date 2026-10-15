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
import com.okamejiiya.petcare.model.AppData
import com.okamejiiya.petcare.model.Pet

enum class Tab(val label: String, val emoji: String) {
    HOME("きょう", "🏠"),
    FOOD("ごはん", "🍚"),
    CALENDAR("カレンダー", "📅"),
    HEALTH("けんこう", "🩺"),
    PET("うちの子", "🐾"),
}

@Composable
fun PetCareRoot(store: PetStore, family: FamilySync, invite: String?, onInviteHandled: () -> Unit) {
    PetCareTheme {
        val data by store.state.collectAsState()
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        var addingPet by rememberSaveable { mutableStateOf(false) }
        var askLink by remember { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val pet = data.selectedPet
        val familyStatus by family.status.collectAsState()

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (pet == null || addingPet) {
                if (addingPet) BackHandler { addingPet = false }
                PetEditor(
                    initial = null,
                    firstTime = pet == null,
                    defaultColor = nextFreeColor(data.pets),
                    onSave = {
                        store.upsertPet(it)
                        store.selectPet(it.id)
                        addingPet = false
                        tab = Tab.HOME
                    },
                    onCancel = if (pet == null) null else ({ addingPet = false }),
                    onDelete = null,
                    modifier = Modifier.systemBarsPadding(),
                    onJoinFamily = if (pet == null && FamilySync.configured) ({ askLink = true }) else null,
                    familyMessage = when (val st = familyStatus) {
                        is FamilySync.Status.Working -> st.message
                        is FamilySync.Status.Error -> st.message
                        is FamilySync.Status.On -> "家族の記録を待っています…（まだペットが登録されていない場合は、ここで登録できます）"
                        else -> null
                    },
                )
            } else {
            Scaffold(
                topBar = { PetSwitcher(data, pet, onSelect = { store.selectPet(it) }, onAdd = { addingPet = true }) },
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                        for (t in Tab.entries) {
                            NavigationBarItem(
                                selected = t == tab,
                                onClick = { tab = t },
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
                    Tab.HOME -> HomeScreen(data, pet, store, snackbar, m, onGoFood = { tab = Tab.FOOD })
                    Tab.FOOD -> FoodScreen(data, pet, store, m)
                    Tab.CALENDAR -> CalendarScreen(data, pet, store, snackbar, m)
                    Tab.HEALTH -> HealthScreen(data, pet, store, m)
                    Tab.PET -> PetScreen(data, pet, store, family, m, onAdd = { addingPet = true })
                }
            }
            }
        }

        // 招待リンクから開かれたとき・最初の画面で「招待リンクで参加」を選んだとき
        if (askLink) InviteLinkDialog(onDismiss = { askLink = false }) { askLink = false; family.join(it); tab = Tab.HOME }
        if (invite != null) {
            if (FamilySync.configured) {
                JoinFamilyDialog(onDismiss = onInviteHandled) { onInviteHandled(); family.join(invite); tab = Tab.HOME }
            } else LaunchedEffect(invite) { onInviteHandled() }
        }
    }
}

@Composable
private fun PetSwitcher(data: AppData, pet: Pet, onSelect: (Long) -> Unit, onAdd: () -> Unit) {
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
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (p in data.pets) {
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
            FilterChip(selected = false, onClick = onAdd, label = { Text("＋ 追加") })
        }
    }
}
