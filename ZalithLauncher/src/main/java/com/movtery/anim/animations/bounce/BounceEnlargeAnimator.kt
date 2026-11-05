package com.movtery.anim.animations.bounce

import android.animation.Animator
import android.animation.ObjectAnimator
import android.view.View
import android.view.animation.PathInterpolator
import com.movtery.anim.animations.BaseAnimator

class BounceEnlargeAnimator: BaseAnimator() {
    override fun getAnimators(target: View): Array<Animator> {
        val interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
        return arrayOf(
            ObjectAnimator.ofFloat(target, "alpha", 0f, 1f).apply { this.interpolator = interpolator },
            ObjectAnimator.ofFloat(target, "scaleX", 0.92f, 1f).apply { this.interpolator = interpolator },
            ObjectAnimator.ofFloat(target, "scaleY", 0.92f, 1f).apply { this.interpolator = interpolator })
    }
}