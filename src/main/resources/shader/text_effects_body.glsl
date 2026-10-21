const float TAU = 6.283185307179586;

// Internals: nothing below needs editing for new effects.

vec3 effectBucket(vec3 c) {
    return floor(round(c * 255.0) / 4.0);
}

bool colorMatches(vec3 a, vec3 b) {
    return all(equal(effectBucket(a), effectBucket(b)));
}

vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

vec3 sweepGradient(float axisPos, float t, float freq, float bias, float hotExp, float hotStrength, vec3 fromColor, vec3 toColor) {
    float phase = (axisPos * freq) + t;
    float wave  = sin(phase);
    float shine = smoothstep(0.0, 1.0, wave + bias);
    float hot   = pow(clamp(shine, 0.0, 1.0), hotExp);
    return mix(fromColor, toColor, shine) + toColor * hot * hotStrength;
}

vec3 paintEffect(int type, vec3 pos, vec3 fromColor, vec3 toColor, float speed, float freq, float bias, float hotExp, float hotStrength, float timeOffset) {
    float t = (GameTime * speed * TAU) + (timeOffset * TAU);

    if (type == EFFECT_PULSE) {
        return sweepGradient(0.0, t, freq, bias, hotExp, hotStrength, fromColor, toColor);
    }
    if (type == EFFECT_WAVE) {
        return sweepGradient(pos.y, t, freq, bias, hotExp, hotStrength, fromColor, toColor);
    }
    if (type == EFFECT_STROBE) {
        float phase = (pos.x * freq) + t;
        float shine = step(0.5, (sin(phase) * 0.5 + 0.5) + bias * 0.5);
        return mix(fromColor, toColor, shine);
    }
    if (type == EFFECT_RAINBOW) {
        float hue = fract((pos.x * freq + t) / TAU);
        return hsv2rgb(vec3(hue, 1.0, 1.0));
    }

    // EFFECT_SWEEP (default)
    return sweepGradient(pos.x, t, freq, bias, hotExp, hotStrength, fromColor, toColor);
}
