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
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Makes players see a skybox from the skyboxes folder, hides their skybox, or goes back to the one that fits where they are. Not saved: it lasts until they leave.
 */
@Name("Set Skybox")
@Description({"Makes players see a skybox from the skyboxes folder, hides their skybox, or goes back to the one that fits where they are. Not saved: it lasts until they leave.", "Needs the pack loaded on a 1.21.6 or newer client."})
@Examples({"set player's skybox to \"stars\"", "hide player's skybox", "reset player's skybox"})
@Since("1.0.0")
public class EffSkybox extends Effect {

    static final String[] PATTERNS = {
            "set %players%'[s] skybox to %string%",
            "hide %players%'[s] skybox",
            "reset %players%'[s] skybox",
    };

    private int pattern;
    private Expression<Player> players;
    private Expression<String> id;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        players = (Expression<Player>) exprs[0];
        if (matchedPattern == 0) id = (Expression<String>) exprs[1];
        return true;
    }

    @Override
    protected void execute(Event event) {
        String value = switch (pattern) {
            case 0 -> id.getSingle(event);
            case 1 -> "none";
            default -> null;
        };
        if (pattern == 0 && value == null) return;
        for (Player player : players.getArray(event)) RepackAPI.setSkybox(player, value);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return switch (pattern) {
            case 0 -> "set " + players.toString(event, debug) + "'s skybox to " + id.toString(event, debug);
            case 1 -> "hide " + players.toString(event, debug) + "'s skybox";
            default -> "reset " + players.toString(event, debug) + "'s skybox";
        };
    }
}
