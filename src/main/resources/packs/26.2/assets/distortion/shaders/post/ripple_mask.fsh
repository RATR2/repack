#version 330

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

// Must match assets/minecraft/shaders/core/text.fsh.
const int MARKER_FIRST_ROW = 8;
const int MARKER_GROUP_STRIDE = 6;
const vec3 MARKER_SIGNATURE = vec3(127.0, 1.0, 126.0) / 255.0;

// Pixel (g, 0) gets a 16-bit mask (r = low byte, g = high byte) of which slots g*16 .. g*16+15 are live, so the
// ripple pass reads 16 pixels instead of checking all 256 signatures per pixel.
void main() {
    ivec2 pixel = ivec2(gl_FragCoord.xy);
    if (pixel.y != 0 || pixel.x >= 16) {
        fragColor = vec4(0.0);
        return;
    }
    int column = 1 + pixel.x * MARKER_GROUP_STRIDE;
    int low = 0;
    int high = 0;
    for (int i = 0; i < 16; i++) {
        vec3 signature = texelFetch(InSampler, ivec2(column, MARKER_FIRST_ROW + i), 0).rgb;
        if (all(lessThanEqual(abs(signature - MARKER_SIGNATURE), vec3(1.5 / 255.0)))) {
            if (i < 8) {
                low |= 1 << i;
            } else {
                high |= 1 << (i - 8);
            }
        }
    }
    fragColor = vec4(float(low) / 255.0, float(high) / 255.0, 0.0, 1.0);
}
