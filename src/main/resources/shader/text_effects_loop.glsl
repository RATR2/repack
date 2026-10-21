for (int i = 0; i < EFFECT_COUNT; i++) {
        if (SHADOW_MODE[i] == SHADOW_LEGACY) {
            // Guess "this is a shadow" from brightness alone.
            bool isShadow = (c.r / 4.0 < 0.23) && (c.g / 4.0 < 0.23) && (c.b / 4.0 < 0.23);
            vec3 sourceColor = isShadow ? c.rgb * 4.0 : c.rgb;
            if (colorMatches(sourceColor, TRIGGER[i])) {
                c.rgb = paintEffect(EFFECT_TYPE[i], Position, G_FROM[i], G_TO[i], SPEED[i], FREQ[i], BIAS[i], HOT_EXP[i], HOT_STR[i], TIME_OFFSET[i]);
                if (isShadow) c.rgb *= 0.25;
                break;
            }
        } else {
            // Check c.rgb against the trigger directly, then scaled up 4x (a match there is that
            // trigger's drop shadow). Works for any brightness.
            if (colorMatches(c.rgb, TRIGGER[i])) {
                c.rgb = paintEffect(EFFECT_TYPE[i], Position, G_FROM[i], G_TO[i], SPEED[i], FREQ[i], BIAS[i], HOT_EXP[i], HOT_STR[i], TIME_OFFSET[i]);
                break;
            }
            if (colorMatches(c.rgb * 4.0, TRIGGER[i])) {
                c.rgb = paintEffect(EFFECT_TYPE[i], Position, G_FROM[i], G_TO[i], SPEED[i], FREQ[i], BIAS[i], HOT_EXP[i], HOT_STR[i], TIME_OFFSET[i]) * 0.25;
                break;
            }
        }
    }
