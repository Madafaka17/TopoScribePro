package com.toposcribe.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShareLocation
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Terrain
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.toposcribe.app.ui.components.AddPointDialog
import com.toposcribe.app.ui.components.TestsDialog
import com.toposcribe.app.ui.components.TopoTopBar
import com.toposcribe.app.ui.screens.altimetria.AltimetriaScreen
import com.toposcribe.app.ui.screens.controlbm.ControlBmScreen
import com.toposcribe.app.ui.screens.exportar.ExportarScreen
import com.toposcribe.app.ui.screens.instrumento.InstrumentoScreen
import com.toposcribe.app.ui.screens.libreta.LibretaScreen
import com.toposcribe.app.ui.screens.planimetria.PlanimetriaScreen
import com.toposcribe.app.ui.screens.planocad.PlanoCadScreen
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class TopoTab(val route: String, val label: String) {
    INSTRUMENTO("instrumento", "1. Instrumento"),
    CONTROL_BM("control_bm", "2. Control/BM"),
    LIBRETA("libreta", "3. Libreta"),
    PLANIMETRIA("planimetria", "4. Planimetría"),
    ALTIMETRIA("altimetria", "5. Altimetría"),
    PLANO_CAD("plano_cad", "6. Plano CAD"),
    EXPORTAR("exportar", "7. Exportar"),
}

private data class BottomTab(val tab: TopoTab, val label: String, val icon: ImageVector)

private val BOTTOM_TABS = listOf(
    BottomTab(TopoTab.INSTRUMENTO, "Inst/Proy", Icons.Filled.Settings),
    BottomTab(TopoTab.CONTROL_BM, "Control", Icons.Filled.ShareLocation),
    BottomTab(TopoTab.LIBRETA, "Libreta", Icons.Filled.EditNote),
    BottomTab(TopoTab.PLANIMETRIA, "Plani", Icons.Filled.Straighten),
    BottomTab(TopoTab.ALTIMETRIA, "Alti", Icons.Filled.Terrain),
    BottomTab(TopoTab.PLANO_CAD, "Plano", Icons.Filled.Layers),
)

@Composable
fun TopoScribeRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.hierarchy?.firstOrNull()?.route

    val appBarViewModel: AppBarViewModel = hiltViewModel()
    val currentProject by appBarViewModel.currentProject.collectAsState()
    var showTests by remember { mutableStateOf(false) }
    var showAddPoint by remember { mutableStateOf(false) }
    val addPointError by appBarViewModel.addPointError.collectAsState()
    val pointCodes by appBarViewModel.pointCodes.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Reloj UTC del encabezado (el diseño lo muestra junto al sistema de coordenadas).
    var clock by remember { mutableStateOf(utcClock()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = utcClock()
            delay(1000)
        }
    }

    if (showTests) {
        TestsDialog(onDismiss = { showTests = false })
    }
    if (showAddPoint) {
        AddPointDialog(
            error = addPointError,
            existingCodes = pointCodes,
            defaultStation = "",
            onDismiss = { showAddPoint = false; appBarViewModel.clearAddPointError() },
            onConfirm = { form ->
                appBarViewModel.addPoint(form) {
                    showAddPoint = false
                    scope.launch { snackbarHostState.showSnackbar("Punto \"${form.code}\" añadido.") }
                }
            },
        )
    }

    fun navigateTo(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Barra inferior ergonómica del diseño: las 6 pestañas de trabajo (Exportar queda solo arriba).
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
                BOTTOM_TABS.forEach { (tab, label, icon) ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = { navigateTo(tab.route) },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        topBar = {
            TopoTopBar(
                title = appBarTitle(currentProject),
                badge = appBarBadge(currentProject),
                subtitle = "${appBarSubtitle(currentProject)}  •  $clock",
                icon = appBarIcon(currentProject),
                onAddPoint = { showAddPoint = true },
                onOpenTests = { showTests = true },
                // Room escribe cada cambio al instante; el botón confirma que todo está persistido.
                onSave = {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            if (currentProject == null) "No hay proyecto activo que guardar." else "Proyecto guardado en el dispositivo.",
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (currentProject?.isDemo == true) {
                DemoBanner(onEliminarDemo = appBarViewModel::deleteCurrentDemo)
            }
            val selectedIndex = TopoTab.entries.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)
            ScrollableTabRow(
                selectedTabIndex = selectedIndex,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                edgePadding = 8.dp,
            ) {
                TopoTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        text = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }

            NavHost(
                navController = navController,
                startDestination = TopoTab.INSTRUMENTO.route,
                modifier = Modifier.fillMaxWidth(),
            ) {
                composable(TopoTab.INSTRUMENTO.route) { InstrumentoScreen() }
                composable(TopoTab.CONTROL_BM.route) { ControlBmScreen() }
                composable(TopoTab.LIBRETA.route) { LibretaScreen() }
                composable(TopoTab.PLANIMETRIA.route) { PlanimetriaScreen() }
                composable(TopoTab.ALTIMETRIA.route) { AltimetriaScreen() }
                composable(TopoTab.PLANO_CAD.route) { PlanoCadScreen() }
                composable(TopoTab.EXPORTAR.route) { ExportarScreen() }
            }
        }
    }
}

/** Banner ámbar permanente de "DATOS DE DEMOSTRACIÓN" -- visible solo cuando el proyecto activo es el demo. */
@Composable
fun DemoBanner(onEliminarDemo: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF8E1))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Warning, contentDescription = null, tint = Color(0xFF8A6D00))
        Text(
            "DATOS DE DEMOSTRACIÓN — levantamiento de ejemplo activo. No representa datos crudos del sensor.",
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF4A3B00),
        )
        TextButton(
            onClick = onEliminarDemo,
            colors = ButtonDefaults.textButtonColors(containerColor = Color(0xFFFFE082), contentColor = Color(0xFF4A3B00)),
        ) {
            Icon(Icons.Filled.DeleteSweep, contentDescription = null)
            Text("Eliminar Demo", modifier = Modifier.padding(start = 4.dp))
        }
    }
}

private val utcFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

private fun utcClock(): String = utcFormatter.format(Instant.now())
