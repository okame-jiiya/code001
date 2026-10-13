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
fun PetCareRoot(store: PetStore) {
    PetCareTheme {
        val data by store.state.collectAsState()
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        var addingPet by rememberSaveable { mutableStateOf(false) }
        val snackbar = remember { SnackbarHostState() }
        val pet = data.selectedPet

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            if (pet == null || addingPet) {
                if (addingPet) BackHandler { addingPet = false }
                PetEditor(
                    initial = null,
                    firstTime = pet == null,
                    onSave = {
                        store.upsertPet(it)
                        store.selectPet(it.id)
                        addingPet = false
                        tab = Tab.HOME
                    },
                    onCancel = if (pet == null) null else ({ addingPet = false }),
                    onDelete = null,
                    modifier = Modifier.systemBarsPadding(),
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
                    Tab.CALENDAR -> CalendarScreen(data, pet, store, m)
                    Tab.HEALTH -> HealthScreen(data, pet, store, m)
                    Tab.PET -> PetScreen(data, pet, store, m, onAdd = { addingPet = true })
                }
            }
            }
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
                    label = { Text("${p.species.emoji} ${p.name}", fontWeight = FontWeight.Bold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                )
            }
            FilterChip(selected = false, onClick = onAdd, label = { Text("＋ 追加") })
        }
    }
}
