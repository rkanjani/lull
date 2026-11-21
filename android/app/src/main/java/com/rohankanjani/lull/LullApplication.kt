package com.rohankanjani.lull

import android.app.Application

/**
 * Keeps the app's one model, as iOS keeps it in `LullApp`, so an errand from the watch finds the same BedJets the
 * screens show, and the screens find what an errand did.
 */
class LullApplication : Application() {
    private var model: AppModel? = null

    override fun onCreate() {
        super.onCreate()
        // Started for a widget or an errand from the watch, Lull draws times before any screen has read the setting.
        Format.followClockSetting(this)
    }

    /**
     * The model, made by whichever needs it first: the screens, or an errand from the watch while Lull is closed.
     * @param fresh A new launch from the launcher or a test. One with launch flags, such as a UI test's, starts from
     *   scratch, as a new launch of the iPhone app does, and so does a plain launch after one with flags, which would
     *   otherwise keep that launch's model: tests run one after another in the same process.
     */
    fun model(options: LaunchOptions = LaunchOptions(), onScreen: Boolean = false, fresh: Boolean = false): AppModel {
        val existing = model
        val startsOver = fresh && (options != LaunchOptions() || existing?.options != options)
        if (existing != null && !startsOver) return existing
        existing?.close()
        return AppModel(this, options, onScreen = onScreen).also { model = it }
    }
}
