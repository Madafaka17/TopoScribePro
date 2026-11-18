package com.toposcribe.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Barra superior replicando el diseño Stitch: ícono + título dinámico ("PROYECTO VACÍO |
 * MODO MANUAL") + badge de método + subtítulo de sistema de coordenadas, con acciones
 * "+ Añadir punto" (secondary), "Pruebas" y "Guardar" (primary) a la derecha.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopoTopBar(
    title: String = "PROYECTO VACÍO | MODO MANUAL",
    badge: String = "ÓPTICO / POLIGONAL",
    subtitle: String = "Sistema: Local (PTL)",
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Architecture,
    onAddPoint: () -> Unit = {},
    onOpenTests: () -> Unit = {},
    onSave: () -> Unit = {},
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
        ),
        title = {
            Row {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Row {
                        Text(
                            title,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Surface(
                            modifier = Modifier.padding(start = 6.dp),
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = MaterialTheme.shapes.extraSmall,
                        ) {
                            Text(
                                badge,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                softWrap = false,
                            )
                        }
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        actions = {
            TextButton(
                onClick = onAddPoint,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSecondary,
                ),
            ) {
                Icon(Icons.Filled.AddCircle, contentDescription = null)
                Text("Añadir punto", modifier = Modifier.padding(start = 4.dp))
            }
            TextButton(onClick = onOpenTests) {
                Icon(Icons.AutoMirrored.Filled.FactCheck, contentDescription = "Pruebas", tint = MaterialTheme.colorScheme.primary)
            }
            TextButton(
                onClick = onSave,
                colors = ButtonDefaults.textButtonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null)
                Text("Guardar", modifier = Modifier.padding(start = 4.dp))
            }
        },
    )
}
