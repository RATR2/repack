package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import io.github.ratr2.repack.distortion.ImpactScene;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class EffImpactScene extends Effect {

    static final String[] PATTERNS = {
            "add [an] %string% focus [point[s]] [of size %-number%] [angled %-number% degrees] at %locations% to [the] impact scene %string%",
            "clear [the] impact scene %string%",
            "(play|show) [the] impact scene %string% for %players% [lasting %-number% tick[s]] [in colo[u]r %-string%]",
    };

    private int pattern;
    private Expression<String> first;
    private @Nullable Expression<Number> size;
    private @Nullable Expression<Number> angle;
    private @Nullable Expression<Location> locations;
    private @Nullable Expression<String> sceneName;
    private @Nullable Expression<Player> players;
    private @Nullable Expression<Number> ticks;
    private @Nullable Expression<String> color;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        first = (Expression<String>) exprs[0];
        switch (matchedPattern) {
            case 0 -> {
                size = (Expression<Number>) exprs[1];
                angle = (Expression<Number>) exprs[2];
                locations = (Expression<Location>) exprs[3];
                sceneName = (Expression<String>) exprs[4];
            }
            case 2 -> {
                players = (Expression<Player>) exprs[1];
                ticks = (Expression<Number>) exprs[2];
                color = (Expression<String>) exprs[3];
            }
            default -> {
            }
        }
        return true;
    }

    private static int rgb(@Nullable String text, int fallback) {
        if (text == null) return fallback;
        String hex = text.trim().replace("#", "");
        try {
            return hex.length() == 6 ? Integer.parseInt(hex, 16) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    protected void execute(Event event) {
        String name = pattern == 0 ? (sceneName == null ? null : sceneName.getSingle(event)) : first.getSingle(event);
        if (name == null) return;
        switch (pattern) {
            case 0 -> {
                String type = first.getSingle(event);
                Number blocks = size == null ? null : size.getSingle(event);
                Number turn = angle == null ? null : angle.getSingle(event);
                if (type == null || locations == null) return;
                ImpactScene scene = RepackAPI.impactScene(name);
                for (Location location : locations.getArray(event)) {
                    if (!scene.add(location, type, blocks == null ? 4.0 : blocks.doubleValue(), null, turn == null ? null : turn.doubleValue())) {
                        Bukkit.getLogger().warning("[Repack] impact scene '" + name + "': the type is radial, explosion, lightning, splotch, slash, lines, shatter or stretched");
                        return;
                    }
                }
            }
            case 1 -> RepackAPI.clearImpactScene(name, false);
            default -> {
                ImpactScene scene = RepackAPI.impactSceneIds().contains(name.toLowerCase()) ? RepackAPI.impactScene(name) : null;
                if (scene == null || players == null) {
                    Bukkit.getLogger().warning("[Repack] there is no impact scene '" + name + "'");
                    return;
                }
                Number length = ticks == null ? null : ticks.getSingle(event);
                int rgb = rgb(color == null ? null : color.getSingle(event), scene.getRgb());
                for (Player player : players.getArray(event)) scene.play(player, length == null ? scene.getTicks() : length.intValue(), rgb, null);
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return switch (pattern) {
            case 0 -> "add a focus point to the impact scene";
            case 1 -> "clear the impact scene " + first.toString(event, debug);
            default -> "play the impact scene " + first.toString(event, debug);
        };
    }
}
