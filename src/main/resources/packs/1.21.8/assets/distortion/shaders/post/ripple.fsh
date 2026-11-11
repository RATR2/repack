#version 150

#moj_import <minecraft:globals.glsl>

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform RippleConfig {
    float Width;
    float Strength;
    float Debug;
};

out vec4 fragColor;

// Must match assets/minecraft/shaders/core/text.fsh and ripple_mask.fsh.
const int MARKER_GROUPS = 16;
const int MARKER_FIRST_ROW = 8;
const int MARKER_GROUP_STRIDE = 6;
const vec2 MARKER_STRIP_PX = vec2(96.0, 16.0);

uniform sampler2D MaskSampler;
uniform sampler2D SceneDepthSampler;

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

const vec3 IMPACT_SIGNATURE = vec3(123.0, 3.0, 122.0) / 255.0;

// The shake and impact marker data covers pixels in the corner (columns 1 to 3 of a shake row, 1 to 6 of an impact row, when its signature is there); the row above stands in for those.
vec2 shakeCover(vec2 px) {
    ivec2 p = ivec2(px);
    if (p.x >= 1 && p.x < 7 && p.y >= SHAKE_FIRST_ROW && p.y < SHAKE_FIRST_ROW + SHAKE_SLOTS) {
        vec3 signature = texelFetch(InSampler, ivec2(1, p.y), 0).rgb;
        bool shake = all(lessThan(abs(signature - SHAKE_SIGNATURE), vec3(1.5 / 255.0)));
        bool impact = all(lessThan(abs(signature - IMPACT_SIGNATURE), vec3(1.5 / 255.0)));
        if ((shake && p.x < 5) || impact) {
            px.y = float(SHAKE_FIRST_ROW + SHAKE_SLOTS) + 0.5;
        }
    }
    return px;
}

// ---- Impact frames ----
// An impact focus marker (a text display at a point in the world with text_opacity 121, see the text shader) uses a row of the shake rows (slot 1 to 15) with a
// signature of its own and five pixels after it: (the point on the screen x, the type), (the point y, the direction), (the color), (the size in screen heights) and
// (the depth value of a point very far away, in 3 bytes).
const int IMPACT_MAX_FOCI = 15;
// How many pixels from an edge the outline reaches.
const float IMPACT_OUTLINE_PX = 3.0;
const float IMPACT_SPIKES = 96.0;

struct ImpactFocus {
    vec2 center;
    int type;
    float radius;
    float angle;
    vec3 color;
};

ImpactFocus impactFoci[IMPACT_MAX_FOCI];
int impactCount = 0;
float impactFar = 0.0;

float impactDecode16(float hi, float lo) {
    return (round(hi * 255.0) + lo) / 255.0;
}

void readImpact() {
    impactCount = 0;
    for (int slot = 0; slot < SHAKE_SLOTS; slot++) {
        int row = SHAKE_FIRST_ROW + slot;
        vec3 signature = texelFetch(InSampler, ivec2(1, row), 0).rgb;
        if (any(greaterThan(abs(signature - IMPACT_SIGNATURE), vec3(1.5 / 255.0))) || impactCount >= IMPACT_MAX_FOCI) {
            continue;
        }
        vec3 a = texelFetch(InSampler, ivec2(2, row), 0).rgb;
        vec3 b = texelFetch(InSampler, ivec2(3, row), 0).rgb;
        vec3 color = texelFetch(InSampler, ivec2(4, row), 0).rgb;
        vec3 d = texelFetch(InSampler, ivec2(5, row), 0).rgb;
        vec3 far = texelFetch(InSampler, ivec2(6, row), 0).rgb;
        impactFoci[impactCount] = ImpactFocus(vec2(impactDecode16(a.r, a.g), impactDecode16(b.r, b.g)) * 3.0 - 1.0, int(round(a.b * 255.0)), max(impactDecode16(d.r, d.g) * 4.0, 0.02), round(b.b * 255.0) / 255.0 * 6.2831853, color);
        impactFar = (round(far.r * 255.0) + (round(far.g * 255.0) + far.b) / 255.0) / 255.0;
        impactCount++;
    }
}

float impactAngleDiff(float a, float b) {
    return mod(a - b + 3.14159265, 6.2831853) - 3.14159265;
}

// A jagged blast with spikes around it.
bool impactBlast(vec2 p, float r, float seed) {
    float tau = 6.2831853;
    float len = length(p);
    float a = atan(p.y, p.x);
    float body = shakeHash(floor(a / tau * 36.0) * 1.7 + seed * 5.3);
    float spike = shakeHash(floor(a / tau * 90.0) + seed * 2.9);
    return len < r * (0.55 + 0.3 * body) || (spike > 0.72 && len < r * (0.9 + 0.7 * spike));
}

// Whether a point p (in screen heights from the focus point, y up the screen) is inside the shape of a type, of size r, along the direction angle (the types
// that have a direction). The shapes change every other tick. Types: 0 radial, 1 explosion, 2 lightning, 3 splotch, 4 slash, 5 speed lines, 6 shatter, 7 stretched.
bool impactShape(vec2 p, int type, float r, float angle) {
    float tau = 6.2831853;
    float len = length(p);
    float a = atan(p.y, p.x);
    float seed = floor(GameTime * 12000.0);
    // p in a frame whose x axis points along the direction.
    vec2 q = vec2(cos(angle) * p.x + sin(angle) * p.y, -sin(angle) * p.x + cos(angle) * p.y);
    if (type == 0) {
        // Radial: a burst of wedges of different lengths.
        float h = shakeHash(floor(a / tau * 72.0) + seed * 3.17);
        return h > 0.4 && len < r * (0.3 + 1.4 * h) && len > r * 0.05;
    }
    if (type == 1) {
        return impactBlast(p, r, seed);
    }
    if (type == 2) {
        // Lightning: thin branches that zigzag away from the point.
        for (int i = 0; i < 6; i++) {
            float base = (float(i) + shakeHash(float(i) * 9.1 + seed)) / 6.0 * tau;
            float zigzag = shakeNoise(len / r * 9.0 + float(i) * 13.0 + seed) * 0.35;
            float reach = r * (0.6 + 0.8 * shakeHash(float(i) + 4.0 + seed));
            if (abs(impactAngleDiff(a, base + zigzag)) * len < r * 0.035 && len < reach) {
                return true;
            }
        }
        return false;
    }
    if (type == 4) {
        // Slash: one long streak through the point along the direction, tapered at both ends.
        float reach = r * 1.8;
        float taper = 1.0 - abs(q.x) / reach;
        float jag = 0.75 + 0.5 * shakeHash(floor(q.x / r * 40.0) + seed);
        return abs(q.x) < reach && abs(q.y) < r * 0.07 * taper * jag + r * 0.004;
    }
    if (type == 5) {
        // Speed lines: parallel lines along the direction, thick near the point and thinning away from it.
        float band = r * 0.1;
        float h = shakeHash(floor(q.y / band) * 1.3 + seed * 2.1);
        float reach = r * (0.5 + 3.0 * h);
        float thick = 0.5 * (1.0 - abs(q.x) / reach);
        return h > 0.45 && abs(q.x) < reach && abs(q.x) > r * 0.12 && fract(q.y / band) < thick;
    }
    if (type == 6) {
        // Shatter: straight cracks with a fork half way along each.
        for (int i = 0; i < 7; i++) {
            float crack = (float(i) + shakeHash(float(i) * 6.3 + seed)) / 7.0 * tau;
            vec2 direction = vec2(cos(crack), sin(crack));
            float reach = r * (0.7 + 0.8 * shakeHash(float(i) * 2.9 + 3.0 + seed));
            float along = dot(p, direction);
            if (along > 0.0 && along < reach && abs(dot(p, vec2(-direction.y, direction.x))) < r * 0.025 * (1.0 - 0.7 * along / reach)) {
                return true;
            }
            float fork = crack + (shakeHash(float(i) * 4.1 + 8.0 + seed) - 0.5) * 1.6;
            vec2 forkDirection = vec2(cos(fork), sin(fork));
            vec2 fromFork = p - direction * reach * 0.5;
            float forkAlong = dot(fromFork, forkDirection);
            if (forkAlong > 0.0 && forkAlong < reach * 0.6 && abs(dot(fromFork, vec2(-forkDirection.y, forkDirection.x))) < r * 0.018 * (1.0 - 0.7 * forkAlong / (reach * 0.6))) {
                return true;
            }
        }
        return false;
    }
    if (type == 7) {
        // Stretched: the blast, long and thin along the direction.
        return impactBlast(vec2(q.x * 0.4, q.y * 1.5), r, seed);
    }
    // Splotch: an irregular blot with drops around it.
    float lobe = shakeNoise(a * 2.3 + seed * 0.37) * 0.5 + shakeNoise(a * 5.1 + 7.0) * 0.25;
    if (len < r * (0.6 + 0.4 * lobe)) {
        return true;
    }
    for (int i = 0; i < 6; i++) {
        float direction = shakeHash(float(i) * 3.7 + 1.0) * tau;
        float away = r * (1.0 + 0.7 * shakeHash(float(i) * 5.1 + 2.0));
        if (length(p - away * vec2(cos(direction), sin(direction))) < r * 0.13 * (0.5 + shakeHash(float(i) * 7.7))) {
            return true;
        }
    }
    return false;
}

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

// The ripple signature of the text shader: a slot is live when this is in the first pixel of its row.
const vec3 RIPPLE_SIGNATURE = vec3(127.0, 1.0, 126.0) / 255.0;

// Whether this pixel of the ripple strip holds data of a live slot, so it is the only kind that needs covering.
bool markerPixelLive(ivec2 p) {
    if (p.x < 1) {
        return false;
    }
    int field = (p.x - 1) % MARKER_GROUP_STRIDE;
    vec3 signature = texelFetch(InSampler, ivec2(p.x - field, p.y), 0).rgb;
    return field <= 4 && all(lessThan(abs(signature - RIPPLE_SIGNATURE), vec3(1.5 / 255.0)));
}

// Marker data pixels overwrite the scene in the corner; cover them with the row just above the strip.
vec3 sceneAt(vec2 uv, vec2 size) {
    vec2 px = uv * size;
    if (px.x < MARKER_STRIP_PX.x && px.y >= float(MARKER_FIRST_ROW) && px.y < float(MARKER_FIRST_ROW) + MARKER_STRIP_PX.y && markerPixelLive(ivec2(px))) {
        px.y = float(MARKER_FIRST_ROW) + MARKER_STRIP_PX.y + 0.5;
    }
    return texture(InSampler, shakeCover(px) / size).rgb;
}

// The look of an impact frame: the whole picture is drawn in two tones only, the color of the first focus point for the light parts and black for the dark ones,
// smeared outward from the nearest focus point by a different amount in each thin slice of the screen so shapes end in jagged spikes, with an outline in the light
// tone where a shape stands in front of something farther away. Then the shapes of the focus points are drawn over it, with a black edge.
vec3 impactColor(vec3 color, vec2 uv, vec2 size) {
    readImpact();
    if (impactCount == 0) {
        return color;
    }
    vec2 aspect = vec2(size.x / size.y, 1.0);
    for (int i = 0; i < impactCount; i++) {
        if (impactShape((uv - impactFoci[i].center) * aspect, impactFoci[i].type, impactFoci[i].radius, impactFoci[i].angle)) {
            return impactFoci[i].color;
        }
    }
    for (int i = 0; i < impactCount; i++) {
        if (impactShape((uv - impactFoci[i].center) * aspect, impactFoci[i].type, impactFoci[i].radius * 1.12 + 0.004, impactFoci[i].angle)) {
            return vec3(0.0);
        }
    }
    int nearest = 0;
    float nearestDistance = 1000.0;
    for (int i = 0; i < impactCount; i++) {
        float d = length((uv - impactFoci[i].center) * aspect);
        if (d < nearestDistance) {
            nearestDistance = d;
            nearest = i;
        }
    }
    vec3 light = impactFoci[0].color;
    // The outline: this pixel is farther than one a few pixels away, so it is next to the edge of something nearer. Depths are compared as distances from the
    // far value, which are in proportion to 1 over the distance in blocks whatever the depth convention.
    float depth = texture(SceneDepthSampler, uv).r;
    float distance = abs(depth - impactFar);
    vec2 texel = 1.0 / size;
    vec2 steps[4] = vec2[](vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0));
    for (int i = 0; i < 4; i++) {
        float other = abs(texture(SceneDepthSampler, uv + steps[i] * texel * IMPACT_OUTLINE_PX).r - impactFar);
        if (other > distance * 1.15 + 0.000002) {
            return light;
        }
    }
    vec2 p = (uv - impactFoci[nearest].center) * aspect;
    float slice = floor(atan(p.y, p.x) * (IMPACT_SPIKES / 6.2831853));
    float pull = pow(shakeHash(slice + floor(GameTime * 12000.0) * 7.13), 2.0) * 0.5;
    vec2 smearUv = p * (1.0 - pull) / aspect + impactFoci[nearest].center;
    vec3 smeared = sceneAt(smearUv, size).rgb;
    float ink = step(0.45, dot(smeared, vec3(0.299, 0.587, 0.114)));
    return mix(vec3(0.0), light, ink);
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

    vec2 sceneUv = shakeUv(texCoord - offset / aspect, size);
    vec3 color = sceneAt(sceneUv, size);
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

    fragColor = vec4(impactColor(color, sceneUv, size), 1.0);
}
