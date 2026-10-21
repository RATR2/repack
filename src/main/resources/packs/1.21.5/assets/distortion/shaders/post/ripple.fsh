#version 150

uniform sampler2D InSampler;

in vec2 texCoord;

uniform float Width;
uniform float Strength;
uniform float Debug;

out vec4 fragColor;

// Must match assets/minecraft/shaders/core/text.fsh and ripple_mask.fsh.
const int MARKER_GROUPS = 16;
const int MARKER_FIRST_ROW = 8;
const int MARKER_GROUP_STRIDE = 6;
const vec2 MARKER_STRIP_PX = vec2(96.0, 16.0);

uniform sampler2D MaskSampler;

struct Ripple {
    vec2 center;
    float radius;
    vec3 color;
    float strength;
    float screenPerBlock;
};

float decode16(float hi, float lo) {
    return (round(hi * 255.0) + lo) / 255.0;
}

// Only called for slots the mask pass marked live, so the signature is already checked.
Ripple readRipple(int slot) {
    int row = MARKER_FIRST_ROW + slot % 16;
    int column = 1 + (slot / 16) * MARKER_GROUP_STRIDE;
    vec3 a = texelFetch(InSampler, ivec2(column + 1, row), 0).rgb;
    vec3 b = texelFetch(InSampler, ivec2(column + 2, row), 0).rgb;
    vec3 c = texelFetch(InSampler, ivec2(column + 3, row), 0).rgb;
    vec3 d = texelFetch(InSampler, ivec2(column + 4, row), 0).rgb;
    Ripple ripple;
    ripple.center = vec2(decode16(a.r, a.g), decode16(a.b, b.r)) * 3.0 - 1.0;
    ripple.radius = decode16(b.g, b.b) * 4.0;
    ripple.color = c;
    ripple.strength = d.r;
    ripple.screenPerBlock = decode16(d.g, d.b) * 2.0;
    return ripple;
}

int groupMask(int group) {
    vec2 bytes = texelFetch(MaskSampler, ivec2(group, 0), 0).rg;
    return int(round(bytes.r * 255.0)) | (int(round(bytes.g * 255.0)) << 8);
}

// Marker data pixels overwrite the scene in the corner; cover them with the row just above the strip.
vec3 sceneAt(vec2 uv, vec2 size) {
    vec2 px = uv * size;
    if (px.x < MARKER_STRIP_PX.x && px.y >= float(MARKER_FIRST_ROW) && px.y < float(MARKER_FIRST_ROW) + MARKER_STRIP_PX.y) {
        px.y = float(MARKER_FIRST_ROW) + MARKER_STRIP_PX.y + 0.5;
    }
    return texture(InSampler, px / size).rgb;
}

void main() {
    vec2 size = vec2(textureSize(InSampler, 0));
    vec2 aspect = vec2(size.x / size.y, 1.0);
    vec2 px = texCoord * size;
    bool debug = Debug > 0.5;

    vec2 offset = vec2(0.0);
    vec3 tint = vec3(0.0);
    float tintWeight = 0.0;
    vec3 debugColor = vec3(-1.0);
    for (int group = 0; group < MARKER_GROUPS; group++) {
        int mask = groupMask(group);
        if (mask == 0) {
            continue;
        }
        for (int i = 0; i < 16; i++) {
            if ((mask & (1 << i)) == 0) {
                continue;
            }
            Ripple ripple = readRipple(group * 16 + i);
            // Width and Strength are in blocks, scaled to the screen at each marker's distance.
            vec2 toCenter = (texCoord - ripple.center) * aspect;
            float dist = length(toCenter);
            float thickness = max(Width * ripple.screenPerBlock, 0.0001);
            float ring = exp(-pow((dist - ripple.radius) / thickness, 2.0)) * ripple.strength;
            if (dist > 0.0001) {
                offset += toCenter / dist * ring * Strength * ripple.screenPerBlock;
            }
            tint += ripple.color * ring;
            tintWeight += ring;

            if (debug) {
                // An outline at the current radius and a cross at the center, in the ripple's color.
                vec2 fromCenter = px - ripple.center * size;
                bool onOutline = abs(length(fromCenter) - ripple.radius * size.y) < 1.0;
                bool onCross = max(abs(fromCenter.x), abs(fromCenter.y)) < 8.0 && min(abs(fromCenter.x), abs(fromCenter.y)) < 1.0;
                if (onOutline || onCross) {
                    debugColor = ripple.color;
                }
            }
        }
    }

    vec3 color = sceneAt(texCoord - offset / aspect, size);
    if (tintWeight > 0.0) {
        color = mix(color, tint / tintWeight, min(tintWeight, 1.0) * 0.5);
    }

    if (debug) {
        if (debugColor.r >= 0.0) {
            color = debugColor;
        }
        // Raw data strip at x4 in the corner: 16 groups of 5 columns, one row per slot, purple first pixel = live.
        if (px.x < MARKER_STRIP_PX.x * 4.0 && px.y < MARKER_STRIP_PX.y * 4.0) {
            color = texelFetch(InSampler, ivec2(int(px.x / 4.0), MARKER_FIRST_ROW + int(px.y / 4.0)), 0).rgb;
        }
    }

    fragColor = vec4(color, 1.0);
}
