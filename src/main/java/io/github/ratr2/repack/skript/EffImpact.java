package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import io.github.ratr2.repack.distortion.DistortionAPI.ImpactFocus;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Plays a short black and white impact frame on players' screens: focus points of a type (radial, explosion, lightning, splotch, slash, lines, shatter, stretched) pointing at locations. More focus points can be added to the frame that is running.
 */
@Name("Impact Frame")
@Description({"Plays a short black and white impact frame on players' screens: focus points of a type (radial, explosion, lightning, splotch, slash, lines, shatter, stretched) pointing at locations. More focus points can be added to the frame that is running.", "Needs the pack loaded on a 1.21.6 or newer client (Fabulous! graphics before 26.3)."})
@Examples({"play an impact frame for player lasting 4 ticks with an \"explosion\" focus point of size 6 at player's location", "add a \"lightning\" focus point of size 8 at player's location to the impact frame of player", "stop the impact frame of player"})
@Since("1.0.1")
public class EffImpact extends Effect {

    static final String[] PATTERNS = {
            "(play|show) [a|an] impact frame for %players% lasting %number% tick[s] [in colo[u]r %-string%] with [a|an] %string% focus [point[s]] [of size %-number%] [angled %-number% degrees] at %locations%",
            "add [a|an] %string% focus [point[s]] [of size %-number%] [angled %-number% degrees] at %locations% to [the] impact frame of %players%",
            "stop [the] impact frame[s] of %players%",
    };

    private static final double DEFAULT_SIZE = 4.0;

    private int pattern;
    private Expression<Player> players;
    private @Nullable Expression<Number> ticks;
    private @Nullable Expression<String> color;
    private @Nullable Expression<String> type;
    private @Nullable Expression<Number> size;
    private @Nullable Expression<Number> angle;
    private @Nullable Expression<Location> locations;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        switch (matchedPattern) {
            case 0 -> {
                players = (Expression<Player>) exprs[0];
                ticks = (Expression<Number>) exprs[1];
                color = (Expression<String>) exprs[2];
                type = (Expression<String>) exprs[3];
                size = (Expression<Number>) exprs[4];
                angle = (Expression<Number>) exprs[5];
                locations = (Expression<Location>) exprs[6];
            }
            case 1 -> {
                type = (Expression<String>) exprs[0];
                size = (Expression<Number>) exprs[1];
                angle = (Expression<Number>) exprs[2];
                locations = (Expression<Location>) exprs[3];
                players = (Expression<Player>) exprs[4];
            }
            default -> players = (Expression<Player>) exprs[0];
        }
        return true;
    }

    /** "55CCFF" or "#55CCFF"; white when it is left out or is not a color. */
    private static int rgb(@Nullable String text) {
        if (text == null) return 0xFFFFFF;
        String hex = text.trim().replace("#", "");
        try {
            return hex.length() == 6 ? Integer.parseInt(hex, 16) : 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    private List<ImpactFocus> foci(Event event) {
        String name = type == null ? null : type.getSingle(event);
        Number blocks = size == null ? null : size.getSingle(event);
        Number turn = angle == null ? null : angle.getSingle(event);
        List<ImpactFocus> list = new ArrayList<>();
        if (name == null || locations == null) return list;
        for (Location location : locations.getArray(event)) {
            list.add(new ImpactFocus(location, name, blocks == null ? DEFAULT_SIZE : blocks.doubleValue(), null, turn == null ? null : turn.doubleValue()));
        }
        return list;
    }

    @Override
    protected void execute(Event event) {
        switch (pattern) {
            case 0 -> {
                Number length = ticks == null ? null : ticks.getSingle(event);
                List<ImpactFocus> list = foci(event);
                if (length == null || length.intValue() <= 0 || list.isEmpty()) return;
                int rgb = rgb(color == null ? null : color.getSingle(event));
                for (Player player : players.getArray(event)) {
                    if (!RepackAPI.impact(player, length.intValue(), rgb, list)) {
                        Bukkit.getLogger().warning("[Repack] impact frame for " + player.getName() + " was not shown: the type is radial, explosion, lightning, splotch, slash, lines, shatter or stretched, and the player needs the pack, a 1.21.6 or newer client and a focus point in their world");
                    }
                }
            }
            case 1 -> {
                for (ImpactFocus focus : foci(event)) {
                    for (Player player : players.getArray(event)) RepackAPI.addImpactFocus(player, focus.getLocation(), focus.getType(), focus.getSize(), focus.getScreenAngle());
                }
            }
            default -> {
                for (Player player : players.getArray(event)) RepackAPI.stopImpact(player);
            }
        }
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return switch (pattern) {
            case 0 -> "play an impact frame for " + players.toString(event, debug) + " lasting " + ticks.toString(event, debug) + " ticks";
            case 1 -> "add a focus point to the impact frame of " + players.toString(event, debug);
            default -> "stop the impact frame of " + players.toString(event, debug);
        };
    }
}
