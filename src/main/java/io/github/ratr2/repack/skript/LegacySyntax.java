package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.ExpressionType;
import io.github.ratr2.repack.Repack;
import io.github.ratr2.repack.block.CustomBlockEvent;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;
import io.github.ratr2.repack.menu.GuiActionEvent;
import io.github.ratr2.repack.menu.LiveGui;

@SuppressWarnings({"deprecation", "removal"})
final class LegacySyntax {

    private LegacySyntax() {
    }

    static void register(Repack plugin) {
        Skript.registerAddon(plugin);

        Skript.registerCondition(CondIsCustomBlock.class, CondIsCustomBlock.PATTERNS);
        Skript.registerCondition(CondPlayerEffects.class, CondPlayerEffects.PATTERNS);

        Skript.registerEffect(EffSpawnRipple.class, EffSpawnRipple.PATTERNS);
        Skript.registerEffect(EffPlaceCustomBlock.class, EffPlaceCustomBlock.PATTERNS);
        Skript.registerEffect(EffRemoveCustomBlock.class, EffRemoveCustomBlock.PATTERNS);
        Skript.registerEffect(EffRegisterCustomBlock.class, EffRegisterCustomBlock.PATTERNS);
        Skript.registerEffect(EffEditCustomBlock.class, EffEditCustomBlock.PATTERNS);
        Skript.registerEffect(EffBlockAnimation.class, EffBlockAnimation.PATTERNS);
        Skript.registerEffect(EffShake.class, EffShake.PATTERNS);
        Skript.registerEffect(EffSkybox.class, EffSkybox.PATTERNS);
        Skript.registerEffect(EffImpact.class, EffImpact.PATTERNS);
        Skript.registerEffect(EffImpactScene.class, EffImpactScene.PATTERNS);
        Skript.registerEffect(EffEditCustomItem.class, EffEditCustomItem.PATTERNS);
        Skript.registerEffect(EffTextRule.class, EffTextRule.PATTERNS);

        Skript.registerExpression(ExprCustomBlockId.class, String.class, ExpressionType.PROPERTY, ExprCustomBlockId.PATTERNS);
        Skript.registerExpression(ExprCustomBlockIds.class, String.class, ExpressionType.SIMPLE, ExprCustomBlockIds.PATTERNS);
        Skript.registerExpression(ExprRepackIds.class, String.class, ExpressionType.SIMPLE, ExprRepackIds.PATTERNS);
        Skript.registerExpression(ExprRipples.class, Long.class, ExpressionType.SIMPLE, ExprRipples.PATTERNS);
        Skript.registerExpression(ExprTextRuleMarker.class, String.class, ExpressionType.SIMPLE, ExprTextRuleMarker.PATTERNS);
        Skript.registerExpression(ExprCustomBlockItem.class, ch.njol.skript.aliases.ItemType.class, ExpressionType.COMBINED, ExprCustomBlockItem.PATTERNS);

        Skript.registerSection(SecCreateGui.class, SecCreateGui.PATTERNS);
        Skript.registerSection(SecMakeSlot.class, SecMakeSlot.PATTERNS);
        Skript.registerSection(SecGuiRun.class, SecGuiRun.PATTERNS);
        Skript.registerEffect(EffGui.class, EffGui.PATTERNS);
        Skript.registerExpression(ExprGui.class, LiveGui.class, ExpressionType.COMBINED, ExprGui.PATTERNS);
        Skript.registerExpression(ExprGuiIds.class, String.class, ExpressionType.SIMPLE, ExprGuiIds.PATTERNS);
        Skript.registerExpression(ExprGuiLayout.class, String.class, ExpressionType.PROPERTY, ExprGuiLayout.PATTERNS);
        Skript.registerExpression(ExprGuiEvent.class, Object.class, ExpressionType.SIMPLE, ExprGuiEvent.PATTERNS);
        Skript.registerEvent("Custom GUI Action", EvtGuiAction.class, GuiActionEvent.class, EvtGuiAction.PATTERNS)
                .description("Fires when an `event: name` action of a GUI file runs.")
                .examples("on custom gui action of \"buy\":");

        Skript.registerEvent("Custom Block Interact", EvtCustomBlockInteract.class, CustomBlockInteractEvent.class, EvtCustomBlockInteract.PATTERNS)
                .description("Fires when a player clicks a custom block.")
                .examples("on custom block interact of \"table\":");
        Skript.registerEvent("Custom Block Action", EvtCustomBlockAction.class, CustomBlockEvent.class, EvtCustomBlockAction.PATTERNS)
                .description("Fires for everything that happens to a custom block's proxy block: break, explode, piston, burn and more.")
                .examples("on custom block explode of \"table\":");
    }
}
