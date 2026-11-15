package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.stream.Stream;

/**
 * Lists of ids: custom items, skyboxes, the animations of a custom block's model, text rules and impact scenes.
 */
@Name("Repack IDs")
@Description({"Lists of ids: custom items, skyboxes, the animations of a custom block's model, text rules and impact scenes."})
@Examples({"send \"Items: %all custom item ids%\" to player", "send \"Skies: %all skybox ids%\" to player", "send \"Animations: %all animations of custom block \"table\"%\" to player"})
@Since("1.0.1")
public class ExprRepackIds extends SimpleExpression<String> {

    static final String[] PATTERNS = {
            "[all [of the]] custom item ids",
            "[all [of the]] skybox ids",
            "[all [of the]] animations of [the] custom block %string%",
            "[all [of the]] text rule ids",
            "[all [of the]] impact scene ids",
    };

    private int pattern;
    private @Nullable Expression<String> block;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        if (matchedPattern == 2) block = (Expression<String>) exprs[0];
        return true;
    }

    @Override
    protected String @Nullable [] get(Event event) {
        Stream<String> ids;
        switch (pattern) {
            case 0 -> ids = RepackAPI.itemIds().stream();
            case 1 -> ids = RepackAPI.skyboxIds().stream();
            case 2 -> {
                String id = block == null ? null : block.getSingle(event);
                if (id == null) return new String[0];
                return RepackAPI.animations(id).toArray(String[]::new);
            }
            case 3 -> ids = RepackAPI.textRuleIds().stream();
            default -> ids = RepackAPI.impactSceneIds().stream();
        }
        return ids.sorted().toArray(String[]::new);
    }

    @Override
    public boolean isSingle() {
        return false;
    }

    @Override
    public Class<? extends String> getReturnType() {
        return String.class;
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return switch (pattern) {
            case 0 -> "all custom item ids";
            case 1 -> "all skybox ids";
            case 2 -> "all animations of custom block " + (block == null ? "" : block.toString(event, debug));
            case 3 -> "all text rule ids";
            default -> "all impact scene ids";
        };
    }
}
