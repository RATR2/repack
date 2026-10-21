#version 330
#extension GL_ARB_separate_shader_objects : require

#include <minecraft:globals.glsl>

uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

// ---- Screen shake ----
// A shake marker (a text display with text_opacity 122, see the text shader) fills row SHAKE_FIRST_ROW + slot of the corner block with a signature
// and two more pixels: (strength high byte, strength low byte, rate) and (up and down against left and right, roll, unused).
const int SHAKE_FIRST_ROW = 40;
const int SHAKE_SLOTS = 16;
const vec3 SHAKE_SIGNATURE = vec3(125.0, 2.0, 124.0) / 255.0;
// What a shake of full strength does: moves the picture this many screen heights, and turns it this many radians.
const float SHAKE_MAX_MOVE = 0.03;
const float SHAKE_MAX_ROLL = 0.05;

float shakeHash(float n) {
    return fract(sin(n * 12.9898) * 43758.5453);
}

// A smooth random value from -1 to 1 that changes without jumps as x grows.
float shakeNoise(float x) {
    float cell = floor(x);
    float t = fract(x);
    t = t * t * (3.0 - 2.0 * t);
    return mix(shakeHash(cell), shakeHash(cell + 1.0), t) * 2.0 - 1.0;
}

// Where the picture is taken from instead of uv while a shake is live. The strength is squared, so a weak shake stays gentle.
vec2 shakeUv(vec2 uv, vec2 size) {
    vec2 move = vec2(0.0);
    float roll = 0.0;
    float power = 0.0;
    float seconds = GameTime * 1200.0;
    for (int slot = 0; slot < SHAKE_SLOTS; slot++) {
        int row = SHAKE_FIRST_ROW + slot;
        vec3 signature = texelFetch(InSampler, ivec2(1, row), 0).rgb;
        if (any(greaterThan(abs(signature - SHAKE_SIGNATURE), vec3(1.5 / 255.0)))) {
            continue;
        }
        vec3 a = texelFetch(InSampler, ivec2(2, row), 0).rgb;
        vec3 b = texelFetch(InSampler, ivec2(3, row), 0).rgb;
        float strength = (round(a.r * 255.0) + a.g) / 255.0;
        float at = seconds * mix(2.0, 30.0, a.b) + float(slot) * 37.0;
        float here = strength * strength;
        move += here * vec2(shakeNoise(at) * 2.0 * (1.0 - b.r), shakeNoise(at + 19.1) * 2.0 * b.r);
        roll += here * b.g * shakeNoise(at + 47.3);
        power += here;
    }
    if (power <= 0.0) {
        return uv;
    }
    power = min(power, 1.0);
    vec2 aspect = vec2(size.x / size.y, 1.0);
    vec2 p = (uv - 0.5) * aspect;
    float angle = roll * SHAKE_MAX_ROLL;
    p = mat2(cos(angle), -sin(angle), sin(angle), cos(angle)) * p;
    // Zoom in a little, so the edge of the picture never shows.
    p *= 1.0 - 2.0 * SHAKE_MAX_MOVE * power - abs(angle);
    return (p + clamp(move, -1.0, 1.0) * SHAKE_MAX_MOVE) / aspect + 0.5;
}

// The shake marker data covers pixels in the corner; the row above them stands in for those.
vec2 shakeCover(vec2 px) {
    if (px.x < 96.0 && px.y >= float(SHAKE_FIRST_ROW) && px.y < float(SHAKE_FIRST_ROW + SHAKE_SLOTS)) {
        px.y = float(SHAKE_FIRST_ROW + SHAKE_SLOTS) + 0.5;
    }
    return px;
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 px = shakeUv(texCoord, size) * size;
    fragColor = vec4(texture(InSampler, shakeCover(px) / size).rgb, 1.0);
}
