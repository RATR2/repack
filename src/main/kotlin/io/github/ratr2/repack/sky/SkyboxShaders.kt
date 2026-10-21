package io.github.ratr2.repack.sky

import io.github.ratr2.repack.pack.PackEra
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * The pack side of the skyboxes. A sky is a one-sprite cube model shown to a player scaled inside out around them; the pack's
 * item shader (the game's own plus a few lines) draws a fragment as a sky when its sprite alpha is a sky code (1 to 24, never
 * drawn for an item), coloring it from the fragment's direction. The time of day and the player's height come in the item's
 * color.
 *
 * The idea of drawing a sky in the item shader of a display that surrounds the player is SkyboxEngine's (Dragoncraft000, MIT,
 * see NOTICE).
 */
object SkyboxShaders {

    private val ERAS = setOf("1.21.8", "1.21.10", "1.21.11", "26.1", "26.2", "26.3")

    fun supports(era: PackEra) = era.id in ERAS

    private const val FUNCTIONS_MARKER = "/*REPACK_SKY_FUNCTIONS*/"
    private const val BRANCH_MARKER = "/*REPACK_SKY_BRANCH*/"

    private fun core(era: PackEra) = if (era.id.startsWith("1.21")) "rendertype_item_entity_translucent_cull" else "item"

    private fun read(path: String) = SkyboxShaders::class.java.getResourceAsStream(path)!!.use { String(it.readBytes(), Charsets.UTF_8) }

    fun files(era: PackEra, skies: List<SkyboxDefinition>): Map<String, ByteArray> {
        if (!supports(era) || skies.isEmpty()) return emptyMap()
        val name = core(era)
        val base = "assets/minecraft/shaders/core/$name"
        val folder = "/skybox/${era.id}/$base"
        val fragment = read("$folder.fsh")
        check(FUNCTIONS_MARKER in fragment && BRANCH_MARKER in fragment) { "The sky template of ${era.id} lost its markers." }
        return mapOf(
            "$base.vsh" to read("$folder.vsh").toByteArray(Charsets.UTF_8),
            "$base.fsh" to fragment.replace(FUNCTIONS_MARKER, functions(skies)).replace(BRANCH_MARKER, branch(era, skies)).toByteArray(Charsets.UTF_8),
        )
    }

    internal fun functions(skies: List<SkyboxDefinition>): String = buildString {
        append(PRELUDE)
        for (sky in skies) {
            when (val source = sky.source) {
                is SkySource.Shader -> {
                    append("\nvec3 sky_${sky.code}(vec3 dir, float skyTime, float skyHeight, float skyAnim) {\n    vec3 color = vec3(0.0);\n")
                    append(source.code.trim('\n').lines().joinToString("\n") { "    $it" })
                    append("\n    return color;\n}\n")
                }
                is SkySource.Texture -> {
                    append("\nvec3 sky_${sky.code}(vec3 dir, float skyTime, float skyHeight, float skyAnim) {\n    return skyPicture(dir, vec2(${source.width}.0, ${source.height}.0));\n}\n")
                }
            }
        }
        append("\nvec3 skyColor(int code, vec3 dir, float skyTime, float skyHeight, float skyAnim) {\n")
        for (sky in skies) append("    if (code == ${sky.code}) {\n        return sky_${sky.code}(dir, skyTime, skyHeight, skyAnim);\n    }\n")
        append("    return vec3(0.0);\n}\n")
        append("\nfloat skyFog(int code) {\n")
        for (sky in skies) append("    if (code == ${sky.code}) {\n        return ${"%.3f".format(java.util.Locale.ROOT, sky.fog)};\n    }\n")
        append("    return 0.25;\n}\n")
    }

    internal fun branch(era: PackEra, skies: List<SkyboxDefinition>): String {
        val detect = """{
        // The sprite of a sky has its code as the alpha of every pixel; a sky is also always far from the camera.
        vec4 skyProbe = texture(Sampler0, texCoord0 * 0.9999999 + vec2(0.00000005));
        int skyCode = int(round(skyProbe.a * 255.0));
        if (skyCode >= 1 && skyCode <= ${skies.size.coerceAtLeast(1)} && abs(skyProbe.a * 255.0 - float(skyCode)) < 0.01 && length(vertexPosition) > 16.0) {
            float skyTime = (round(baseColor.r * 255.0) * 256.0 + round(baseColor.g * 255.0)) / 24000.0;
            float skyHeight = round(baseColor.b * 255.0) / 255.0 * 384.0 - 64.0;
            vec4 sky = vec4(skyColor(skyCode, normalize(vertexPosition), skyTime, skyHeight, GameTime * 1200.0), 1.0);
            float fogged = skyFog(skyCode);
"""
        val finish = if (era.id == "26.3") """#ifdef OIT_ALPHA_ONLY
            executeAlphaOnlyPhase(gl_FragCoord.z, 1.0);
            return;
#else
#ifdef OIT_ACCUMULATE
            sky = sampleColorForAccumulation(sky);
            vec4 skyFogColor = vec4(FogColor.rgb * sky.a, FogColor.a);
#else
            vec4 skyFogColor = FogColor;
#endif
            fragColor = apply_fog(sky, sphericalVertexDistance * fogged, cylindricalVertexDistance * fogged, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, skyFogColor);
            return;
#endif
        }
    }
""" else """            fragColor = apply_fog(sky, sphericalVertexDistance * fogged, cylindricalVertexDistance * fogged, FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
            return;
        }
    }
"""
        return "    " + detect + finish
    }

    private val PRELUDE = """// ---- Repack skyboxes ----
const float SKY_PI = 3.14159265359;

float skyHash(vec3 p) {
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

// Smooth random value from 0 to 1 that changes without jumps as p moves.
float skyNoise(vec3 x) {
    vec3 cell = floor(x);
    vec3 f = fract(x);
    f = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(mix(skyHash(cell), skyHash(cell + vec3(1.0, 0.0, 0.0)), f.x), mix(skyHash(cell + vec3(0.0, 1.0, 0.0)), skyHash(cell + vec3(1.0, 1.0, 0.0)), f.x), f.y),
        mix(mix(skyHash(cell + vec3(0.0, 0.0, 1.0)), skyHash(cell + vec3(1.0, 0.0, 1.0)), f.x), mix(skyHash(cell + vec3(0.0, 1.0, 1.0)), skyHash(cell + vec3(1.0, 1.0, 1.0)), f.x), f.y),
        f.z);
}

// Several layers of skyNoise: 0 to 1, cloudy.
float skyFbm(vec3 p) {
    float sum = 0.0;
    float weight = 0.5;
    for (int i = 0; i < 4; i++) {
        sum += weight * skyNoise(p);
        p = p * 2.03 + 11.7;
        weight *= 0.5;
    }
    return sum;
}

// Points of light in every direction of dir: density from 0 to 1.
float skyStars(vec3 dir, float density) {
    vec3 scaled = dir * 160.0;
    vec3 cell = floor(scaled);
    float chance = skyHash(cell);
    float size = smoothstep(0.45, 0.0, length(fract(scaled) - 0.5));
    return step(1.0 - density * 0.03, chance) * size * (0.5 + 0.5 * skyHash(cell + 7.0));
}

// Where the sun is for a time of day from 0 (sunrise) to 1: x is east, y is up. Below the horizon in the night.
vec3 skySunDirection(float skyTime) {
    float angle = skyTime * 2.0 * SKY_PI;
    return normalize(vec3(cos(angle), sin(angle), 0.2));
}

// From horizon at the level of the eye to top straight up.
vec3 skyGradient(vec3 top, vec3 horizon, vec3 dir) {
    return mix(horizon, top, pow(clamp(dir.y, 0.0, 1.0), 0.5));
}

// A picture of the whole sky (twice as wide as it is high), as it is in the sprite.
vec3 skyPicture(vec3 dir, vec2 imageSize) {
    vec2 atlas = vec2(textureSize(Sampler0, 0));
    vec2 origin = floor(texCoord0 * atlas + 0.2) / atlas;
    vec2 uv = vec2(atan(dir.x, dir.z) / SKY_PI * 0.5 + 0.5, 0.5 - asin(clamp(dir.y, -1.0, 1.0)) / SKY_PI);
    vec2 pixel = clamp(uv * imageSize, vec2(0.5), imageSize - vec2(0.5));
    return textureLod(Sampler0, origin + pixel / atlas, 0.0).rgb;
}
"""

    fun assets(skies: List<SkyboxDefinition>): Map<String, ByteArray> {
        val files = LinkedHashMap<String, ByteArray>()
        for (sky in skies) {
            val texture = when (val source = sky.source) {
                is SkySource.Texture -> coded(source.png, sky.code)
                is SkySource.Shader -> plain(sky.code)
            }
            files["assets/repack/textures/block/sky/${sky.id}.png"] = texture
            // A picture sky is read from the top left corner of its sprite, so the faces only cover a speck of it.
            val uv = if (sky.source is SkySource.Texture) "[0, 0, 0.001, 0.001]" else "[0, 0, 16, 16]"
            val faces = listOf("north", "east", "south", "west", "up", "down").joinToString(", ") { """"$it": {"uv": $uv, "texture": "#0", "tintindex": 0}""" }
            files["assets/repack/models/block/sky/${sky.id}.json"] = """{
  "textures": {"0": "repack:block/sky/${sky.id}", "particle": "repack:block/sky/${sky.id}"},
  "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "shade": false, "faces": {$faces}}]
}
""".toByteArray(Charsets.UTF_8)
            files["assets/repack/items/sky/${sky.id}.json"] = """{
  "model": {"type": "minecraft:model", "model": "repack:block/sky/${sky.id}", "tints": [{"type": "minecraft:dye", "default": -1}]}
}
""".toByteArray(Charsets.UTF_8)
        }
        return files
    }

    private fun plain(code: Int): ByteArray {
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (x in 0 until 16) for (y in 0 until 16) image.setRGB(x, y, (code shl 24) or 0xFFFFFF)
        return png(image)
    }

    private fun coded(bytes: ByteArray, code: Int): ByteArray {
        val source = ImageIO.read(java.io.ByteArrayInputStream(bytes))
        val image = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until source.height) for (x in 0 until source.width) image.setRGB(x, y, (code shl 24) or (source.getRGB(x, y) and 0xFFFFFF))
        return png(image)
    }

    private fun png(image: BufferedImage) = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()

    fun allFiles(era: PackEra, skies: List<SkyboxDefinition>): Map<String, ByteArray> =
        if (skies.isEmpty()) emptyMap() else assets(skies) + files(era, skies)
}
