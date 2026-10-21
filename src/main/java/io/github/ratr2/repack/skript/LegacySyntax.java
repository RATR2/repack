package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.ExpressionType;
import io.github.ratr2.repack.Repack;
import io.github.ratr2.repack.block.CustomBlockEvent;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;

@SuppressWarnings({"deprecation", "removal"})
final class LegacySyntax {

    private LegacySyntax() {
    }

    static void register(Repack plugin) {
        Skript.registerAddon(plugin);

        Skript.registerCondition(CondIsCustomBlock.class, CondIsCustomBlock.PATTERNS);

        Skript.registerEffect(EffSpawnRipple.class, EffSpawnRipple.PATTERNS);
        Skript.registerEffect(EffPlaceCustomBlock.class, EffPlaceCustomBlock.PATTERNS);
        Skript.registerEffect(EffRemoveCustomBlock.class, EffRemoveCustomBlock.PATTERNS);
        Skript.registerEffect(EffRegisterCustomBlock.class, EffRegisterCustomBlock.PATTERNS);
        Skript.registerEffect(EffEditCustomBlock.class, EffEditCustomBlock.PATTERNS);
        Skript.registerEffect(EffBlockAnimation.class, EffBlockAnimation.PATTERNS);
        Skript.registerEffect(EffShake.class, EffShake.PATTERNS);
        Skript.registerEffect(EffSkybox.class, EffSkybox.PATTERNS);

        Skript.registerExpression(ExprCustomBlockId.class, String.class, ExpressionType.PROPERTY, ExprCustomBlockId.PATTERNS);
        Skript.registerExpression(ExprCustomBlockIds.class, String.class, ExpressionType.SIMPLE, ExprCustomBlockIds.PATTERNS);
        Skript.registerExpression(ExprCustomBlockItem.class, ch.njol.skript.aliases.ItemType.class, ExpressionType.COMBINED, ExprCustomBlockItem.PATTERNS);

        Skript.registerEvent("Custom Block Interact", EvtCustomBlockInteract.class, CustomBlockInteractEvent.class, EvtCustomBlockInteract.PATTERNS)
                .description("Fires when a player clicks a custom block.")
                .examples("on custom block interact of \"table\":");
        Skript.registerEvent("Custom Block Action", EvtCustomBlockAction.class, CustomBlockEvent.class, EvtCustomBlockAction.PATTERNS)
                .description("Fires for everything that happens to a custom block's proxy block: break, explode, piston, burn and more.")
                .examples("on custom block explode of \"table\":");
    }
}
