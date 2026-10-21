// 0xRRGGBB integer literal to vec3
#define HEX(h) vec3(float((h >> 16) & 0xFF) / 255.0, float((h >> 8) & 0xFF) / 255.0, float(h & 0xFF) / 255.0)

// Effect types
#define EFFECT_SWEEP   0
#define EFFECT_PULSE   1
#define EFFECT_WAVE    2
#define EFFECT_STROBE  3
#define EFFECT_RAINBOW 4

// Shadow detection modes.
// SHADOW_MATCH (default): match the color against each trigger, and against each trigger scaled up 4x (a match there is
//   that trigger's drop shadow). Works for triggers of any brightness.
// SHADOW_LEGACY: guess "this is a shadow" from brightness alone. Treats dark trigger colors as shadows of themselves, so
//   their shine never fires. Kept as an opt-in.
#define SHADOW_MATCH  0
#define SHADOW_LEGACY 1
