package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Effect;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * register custom block "table" using model "ritual_table", unregister custom block "table" and
 * reload custom blocks. Registered and unregistered blocks last until the next reload.
 */
public class EffRegisterCustomBlock extends Effect {

    static final String[] PATTERNS = {
            "register [a] custom block %string% [(with|using) model %-string%]",
            "unregister [a] custom block %string%",
            "reload custom blocks"
    };

    private int pattern;
    private @Nullable Expression<String> id;
    private @Nullable Expression<String> model;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        if (matchedPattern != 2) id = (Expression<String>) exprs[0];
        if (matchedPattern == 0) model = (Expression<String>) exprs[1];
        return true;
    }

    @Override
    protected void execute(Event event) {
        if (pattern == 2) {
            RepackAPI.reload().forEach(error -> Bukkit.getLogger().warning("[Repack] " + error));
            return;
        }
        String wanted = id == null ? null : id.getSingle(event);
        if (wanted == null) return;
        if (pattern == 1) {
            RepackAPI.unregister(wanted);
            return;
        }
        String file = model == null ? null : model.getSingle(event);
        String error = RepackAPI.register(wanted, file == null ? wanted : file);
        if (error != null) Bukkit.getLogger().warning("[Repack] Could not register '" + wanted + "': " + error);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return switch (pattern) {
            case 0 -> "register custom block " + id.toString(event, debug) + (model != null ? " using model " + model.toString(event, debug) : "");
            case 1 -> "unregister custom block " + id.toString(event, debug);
            default -> "reload custom blocks";
        };
    }
}
