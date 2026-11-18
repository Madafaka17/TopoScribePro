package com.toposcribe.app.data.repository

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Estado compartido minimo (equivalente al uiStore de Zustand de la app web): que proyecto esta
 * activo. Un Singleton de Hilt en vez de guardarlo en cada ViewModel por separado, para que todas
 * las pantallas vean el mismo proyecto activo sin pasarlo a mano por la navegacion.
 *
 * El id se guarda tambien en el dispositivo: sin eso, cada vez que la app se reabre (o Android la
 * cierra en segundo plano) el proyecto activo se perdia y se abria otro distinto, con lo que los
 * datos del usuario parecian haber desaparecido. */
@Singleton
class CurrentProjectHolder @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _currentProjectId = MutableStateFlow<Long?>(
        prefs.getLong(KEY_PROJECT_ID, NO_PROJECT).takeIf { it != NO_PROJECT },
    )
    val currentProjectId: StateFlow<Long?> = _currentProjectId.asStateFlow()

    fun select(projectId: Long?) {
        _currentProjectId.value = projectId
        prefs.edit().apply {
            if (projectId == null) remove(KEY_PROJECT_ID) else putLong(KEY_PROJECT_ID, projectId)
        }.apply()
    }

    private companion object {
        const val PREFS_NAME = "toposcribe_state"
        const val KEY_PROJECT_ID = "current_project_id"
        const val NO_PROJECT = -1L
    }
}
