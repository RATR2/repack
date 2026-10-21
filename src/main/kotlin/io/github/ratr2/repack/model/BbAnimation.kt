package io.github.ratr2.repack.model

/** One keyframe of one channel (`position`, `rotation` or `scale`). before is the value arriving at it and after the value leaving it, which differ for a jump. */
class BbKeyframe(val channel: String, val time: Double, val before: Vec3, val after: Vec3, val interpolation: String)

class BbAnimation(val name: String, val length: Double, val loop: String, val animators: Map<String, List<BbKeyframe>>)

/** How an animation repeats: LOOP over and over, ONCE one time and then back to the pose the model is built in, HOLD one time and then stays in its last pose. */
enum class AnimationMode {
    LOOP, ONCE, HOLD;

    companion object {
        fun of(text: String?): AnimationMode = entries.firstOrNull { it.name.equals(text?.trim(), ignoreCase = true) } ?: ONCE
    }
}

class BoneTransform(val position: Vec3, val rotation: Vec3, val scale: Vec3) {
    companion object {
        val NONE = BoneTransform(Vec3.ZERO, Vec3.ZERO, Vec3(1.0, 1.0, 1.0))
    }
}

/** Evaluates an animation at a time, the way Blockbench plays it: linear and catmull-rom curves and steps; bezier handles are treated as catmull-rom. */
object AnimationSampler {

    const val TWEEN_BACK = 0.25

    fun pose(animation: BbAnimation, seconds: Double, mode: AnimationMode = AnimationMode.of(animation.loop)): Map<String, BoneTransform> = when (mode) {
        AnimationMode.LOOP -> sample(animation, seconds % animation.length)
        AnimationMode.HOLD -> sample(animation, seconds.coerceIn(0.0, animation.length))
        AnimationMode.ONCE -> when {
            seconds <= animation.length -> sample(animation, seconds.coerceAtLeast(0.0))
            seconds >= animation.length + TWEEN_BACK -> emptyMap()
            else -> {
                val left = 1 - (seconds - animation.length) / TWEEN_BACK
                sample(animation, animation.length).mapValues { (_, t) ->
                    // A turn of 360 degrees or more is the same pose as the one it ends at, so only what is left over is settled: otherwise the model spins back.
                    BoneTransform(
                        Vec3(t.position.x * left, t.position.y * left, t.position.z * left),
                        Vec3(wrap(t.rotation.x) * left, wrap(t.rotation.y) * left, wrap(t.rotation.z) * left),
                        Vec3(1 + (t.scale.x - 1) * left, 1 + (t.scale.y - 1) * left, 1 + (t.scale.z - 1) * left),
                    )
                }
            }
        }
    }

    private fun wrap(degrees: Double): Double = ((degrees + 180) % 360 + 360) % 360 - 180

    fun sample(animation: BbAnimation, time: Double): Map<String, BoneTransform> {
        val result = HashMap<String, BoneTransform>()
        for ((bone, keyframes) in animation.animators) {
            fun channel(name: String, default: Vec3) = value(keyframes.filter { it.channel == name }.sortedBy { it.time }, time, default)
            result[bone] = BoneTransform(channel("position", Vec3.ZERO), channel("rotation", Vec3.ZERO), channel("scale", Vec3(1.0, 1.0, 1.0)))
        }
        return result
    }

    private fun value(keys: List<BbKeyframe>, time: Double, default: Vec3): Vec3 {
        if (keys.isEmpty()) return default
        if (time <= keys.first().time) return keys.first().before
        if (time >= keys.last().time) return keys.last().after
        val next = keys.indexOfFirst { it.time > time }
        val a = keys[next - 1]
        val b = keys[next]
        if (a.interpolation == "step") return a.after
        val t = (time - a.time) / (b.time - a.time)
        if (a.interpolation == "catmullrom" || b.interpolation == "catmullrom" || a.interpolation == "bezier" || b.interpolation == "bezier") {
            val p0 = keys.getOrNull(next - 2)?.after ?: a.after
            val p3 = keys.getOrNull(next + 1)?.before ?: b.before
            return Vec3(catmull(p0.x, a.after.x, b.before.x, p3.x, t), catmull(p0.y, a.after.y, b.before.y, p3.y, t), catmull(p0.z, a.after.z, b.before.z, p3.z, t))
        }
        return Vec3(lerp(a.after.x, b.before.x, t), lerp(a.after.y, b.before.y, t), lerp(a.after.z, b.before.z, t))
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    private fun catmull(p0: Double, p1: Double, p2: Double, p3: Double, t: Double): Double {
        val t2 = t * t
        val t3 = t2 * t
        return 0.5 * (2 * p1 + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }
}
