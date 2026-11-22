package io.github.fredjunior54.localbell.di

import android.content.Context
import io.github.fredjunior54.localbell.data.SettingsRepository

/**
 * Simple manual dependency injection container. Provides singleton instances of app-wide
 * dependencies.
 */
object AppModule {
    private var _settingsRepository: SettingsRepository? = null

    /** Initializes the DI container. Must be called in Application.onCreate(). */
    fun init(context: Context) {
        val appContext = context.applicationContext
        _settingsRepository = SettingsRepository(appContext)
    }

    val settingsRepository: SettingsRepository
        get() =
            checkNotNull(_settingsRepository) {
                "AppModule not initialized. Call init() in Application.onCreate()"
            }
}
