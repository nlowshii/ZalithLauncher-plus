package com.movtery.anim.animations.bounce

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.PathInterpolator
import com.movtery.anim.animations.BaseAnimator

class BounceShrinkAnimator: BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        val interpolator = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)
        return arrayOf(
            ObjectAnimator.ofFloat(target, "alpha", 1f, 0f).apply { this.interpolator = interpolator },
            ObjectAnimator.ofFloat(target, "scaleX", 1f, 0.92f).apply { this.interpolator = interpolator },
            ObjectAnimator.ofFloat(target, "scaleY", 1f, 0.92f).apply { this.interpolator = interpolator })
    }
}