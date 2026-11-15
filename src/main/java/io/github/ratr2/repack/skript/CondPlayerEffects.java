package io.github.ratr2.repack.skript;

import ch.njol.skript.doc.Description;
import ch.njol.skript.doc.Examples;
import ch.njol.skript.doc.Name;
import ch.njol.skript.doc.Since;
import ch.njol.skript.lang.Condition;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser.ParseResult;
import ch.njol.util.Kleenean;
import io.github.ratr2.repack.RepackAPI;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

/**
 * Checks whether a player has loaded the Repack resource pack, or whether their client can show screen effects (ripples, shake, impact frames).
 */
@Name("Player Has Pack / Can See Screen Effects")
@Description({"Checks whether a player has loaded the Repack resource pack, or whether their client can show screen effects (ripples, shake, impact frames).", "Screen effects need the pack loaded on a 1.21.6 or newer client, and Fabulous! graphics before 26.3."})
@Examples({"if player has the repack pack:", "if player does not have the repack pack:", "if victim can see screen effects:", "if player can't see screen effects:"})
@Since("1.0.1")
public class CondPlayerEffects extends Condition {

    static final String[] PATTERNS = {
            "%players% (has|have) [loaded] [the] repack pack",
            "%players% (hasn't|has not|haven't|have not|doesn't have|does not have|don't have|do not have) [loaded] [the] repack pack",
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
