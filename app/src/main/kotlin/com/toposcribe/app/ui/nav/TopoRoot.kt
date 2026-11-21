package com.toposcribe.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.toposcribe.app.data.repository.CurrentProjectHolder
import com.toposcribe.app.data.repository.ProjectRepository
import com.toposcribe.app.data.repository.coordinateSystemText
import com.toposcribe.app.ui.components.LocalNotify
import com.toposcribe.app.ui.components.TestsDialog
import com.toposcribe.app.ui.screens.altimetria.AltimetriaScreen
import com.toposcribe.app.ui.screens.curvas.CurvasScreen
import com.toposcribe.app.ui.screens.informe.InformeScreen
import com.toposcribe.app.ui.screens.libreta.LibretaScreen
import com.toposcribe.app.ui.screens.planimetria.PlanimetriaScreen
import com.toposcribe.app.ui.screens.planocad.PlanoCadScreen
import com.toposcribe.app.ui.screens.proyecto.ProyectoScreen
import com.toposcribe.data.local.entity.ProjectEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Navegación principal: Proyecto, Libreta de campo, Planimetría, Altimetría, Curvas de nivel, Plano CAD e Informe. */
enum class TopoTab(val label: String) {
    PROYECTO("Proyecto"), LIBRETA("Libreta de campo"), PLANIMETRIA("Planimetría"), ALTIMETRIA("Altimetría"),
    CURVAS("Curvas de nivel"), PLANO("Plano CAD"), INFORME("Informe"),
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RootViewModel @Inject constructor(private val projects: ProjectRepository, holder: CurrentProjectHolder) : ViewModel() {
    val current: StateFlow<ProjectEntity?> = holder.currentProjectId
        .flatMapLatest { id -> if (id == null) flowOf(null) else projects.observeById(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun TopoScribeRoot(vm: RootViewModel = hiltViewModel()) {
    val project by vm.current.collectAsState()
    var tabIndex by rememberSaveable { mutableStateOf(0) }
    var showTests by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val notify: (String) -> Unit = { msg -> scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(msg) } }

    if (showTests) TestsDialog { showTests = false }

    CompositionLocalProvider(LocalNotify provides notify) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                CenterAlignedTopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    title = {
                        Column {
                            Text(project?.let { it.name.ifBlank { "(proyecto sin nombre)" } } ?: "Sin proyecto abierto", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                            Text(project?.let { "Sistema: ${coordinateSystemText(it)} · ${it.unitsDistance}" } ?: "TopoScribe Pro", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                    },
                    actions = { IconButton(onClick = { showTests = true }) { Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = "Casos de prueba", tint = MaterialTheme.colorScheme.primary) } },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().semantics { testTagsAsResourceId = true }) {
                ScrollableTabRow(selectedTabIndex = tabIndex, containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, edgePadding = 8.dp) {
                    TopoTab.entries.forEachIndexed { i, t ->
                        Tab(selected = i == tabIndex, onClick = { tabIndex = i }, text = { Text(t.label, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.testTag("tab_${t.name}"))
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val m = Modifier.fillMaxSize()
                    when (TopoTab.entries[tabIndex]) {
                        TopoTab.PROYECTO -> ProyectoScreen(m)
                        TopoTab.LIBRETA -> LibretaScreen(m)
                        TopoTab.PLANIMETRIA -> PlanimetriaScreen(m)
                        TopoTab.ALTIMETRIA -> AltimetriaScreen(m)
                        TopoTab.CURVAS -> CurvasScreen(m)
                        TopoTab.PLANO -> PlanoCadScreen(m)
                        TopoTab.INFORME -> InformeScreen(m, hasProject = project != null)
                    }
                }
            }
        }
    }
}
