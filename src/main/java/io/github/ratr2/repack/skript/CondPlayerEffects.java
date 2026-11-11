package io.github.ratr2.repack.skript;

import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

public class CondPlayerEffects extends Condition {

    static final String[] PATTERNS = {
            "%players% (has|have) [loaded] [the] repack pack",
            "%players% (hasn't|has not|haven't|have not) [loaded] [the] repack pack",
            "%players% (can|are able to) see [repack] screen effects",
            "%players% (can't|cannot|can not|are not able to) see [repack] screen effects",
    };

    private Expression<Player> players;
    private boolean screenEffects;

    @Override
    @SuppressWarnings("unchecked")
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        players = (Expression<Player>) exprs[0];
        screenEffects = matchedPattern >= 2;
        setNegated(matchedPattern % 2 == 1);
        return true;
    }

    @Override
    public boolean check(Event event) {
        return players.check(event, screenEffects ? RepackAPI::canShowScreenEffects : RepackAPI::hasPack, isNegated());
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return players.toString(event, debug) + (isNegated() ? " has not" : " has") + (screenEffects ? " screen effects" : " loaded the repack pack");
    }
}
