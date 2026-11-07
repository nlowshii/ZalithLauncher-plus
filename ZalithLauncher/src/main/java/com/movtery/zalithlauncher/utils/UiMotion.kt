package com.movtery.zalithlauncher.utils

import android.view.View
import android.view.animation.DecelerateInterpolator
import com.movtery.zalithlauncher.setting.AllSettings

object UiMotion {
    @JvmStatic
    fun enter(vararg views: View) {
        if (!AllSettings.animation.getValue()) return
        views.forEachIndexed { index, view ->
            val distance = view.resources.displayMetrics.density * 18f
            view.alpha = 0f
            view.translationY = distance
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(55L * index)
                .setDuration(280L)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }
}
