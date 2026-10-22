package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.aliases.ItemType;
import io.github.ratr2.repack.Repack;
import io.github.ratr2.repack.block.CustomBlockEvent;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;
import io.github.ratr2.repack.menu.GuiActionEvent;
import io.github.ratr2.repack.menu.LiveGui;
import org.skriptlang.skript.addon.SkriptAddon;
import org.skriptlang.skript.bukkit.registration.BukkitSyntaxInfos;
import org.skriptlang.skript.registration.SyntaxInfo;
import org.skriptlang.skript.registration.SyntaxRegistry;

final class ModernSyntax {

    private ModernSyntax() {
    }

    static void register(Repack plugin) {
        SkriptAddon addon = Skript.instance().registerAddon(Repack.class, "Repack");
        SyntaxRegistry registry = addon.syntaxRegistry();

        registry.register(SyntaxRegistry.CONDITION, SyntaxInfo.builder(CondIsCustomBlock.class)
                .supplier(CondIsCustomBlock::new).addPatterns(CondIsCustomBlock.PATTERNS).build());

        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffSpawnRipple.class)
                .supplier(EffSpawnRipple::new).addPatterns(EffSpawnRipple.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffPlaceCustomBlock.class)
                .supplier(EffPlaceCustomBlock::new).addPatterns(EffPlaceCustomBlock.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffRemoveCustomBlock.class)
                .supplier(EffRemoveCustomBlock::new).addPatterns(EffRemoveCustomBlock.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffEditCustomBlock.class)
                .supplier(EffEditCustomBlock::new).addPatterns(EffEditCustomBlock.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffBlockAnimation.class)
                .supplier(EffBlockAnimation::new).addPatterns(EffBlockAnimation.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffShake.class)
                .supplier(EffShake::new).addPatterns(EffShake.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffSkybox.class)
                .supplier(EffSkybox::new).addPatterns(EffSkybox.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffRegisterCustomBlock.class)
                .supplier(EffRegisterCustomBlock::new).addPatterns(EffRegisterCustomBlock.PATTERNS).build());

        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprCustomBlockId.class, String.class)
                .supplier(ExprCustomBlockId::new).addPatterns(ExprCustomBlockId.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprCustomBlockIds.class, String.class)
                .supplier(ExprCustomBlockIds::new).addPatterns(ExprCustomBlockIds.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprCustomBlockItem.class, ItemType.class)
                .supplier(ExprCustomBlockItem::new).addPatterns(ExprCustomBlockItem.PATTERNS).build());

        registry.register(SyntaxRegistry.SECTION, SyntaxInfo.builder(SecCreateGui.class)
                .supplier(SecCreateGui::new).addPatterns(SecCreateGui.PATTERNS).build());
        registry.register(SyntaxRegistry.SECTION, SyntaxInfo.builder(SecMakeSlot.class)
                .supplier(SecMakeSlot::new).addPatterns(SecMakeSlot.PATTERNS).build());
        registry.register(SyntaxRegistry.SECTION, SyntaxInfo.builder(SecGuiRun.class)
                .supplier(SecGuiRun::new).addPatterns(SecGuiRun.PATTERNS).build());
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffGui.class)
                .supplier(EffGui::new).addPatterns(EffGui.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprGui.class, LiveGui.class)
                .supplier(ExprGui::new).addPatterns(ExprGui.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprGuiIds.class, String.class)
                .supplier(ExprGuiIds::new).addPatterns(ExprGuiIds.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprGuiLayout.class, String.class)
                .supplier(ExprGuiLayout::new).addPatterns(ExprGuiLayout.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprGuiEvent.class, Object.class)
                .supplier(ExprGuiEvent::new).addPatterns(ExprGuiEvent.PATTERNS).build());
        registry.register(BukkitSyntaxInfos.Event.KEY, BukkitSyntaxInfos.Event.builder(EvtGuiAction.class, "Custom GUI Action")
                .supplier(EvtGuiAction::new)
                .addEvent(GuiActionEvent.class)
                .addDescription("Fires when an `event: name` action of a GUI file runs.")
                .addExample("on custom gui action of \"buy\":")
                .addPatterns(EvtGuiAction.PATTERNS)
                .build());

        registry.register(BukkitSyntaxInfos.Event.KEY, BukkitSyntaxInfos.Event.builder(EvtCustomBlockInteract.class, "Custom Block Interact")
                .supplier(EvtCustomBlockInteract::new)
                .addEvent(CustomBlockInteractEvent.class)
                .addDescription("Fires when a player clicks a custom block.")
                .addExample("on custom block interact of \"table\":")
                .addPatterns(EvtCustomBlockInteract.PATTERNS)
                .build());
        registry.register(BukkitSyntaxInfos.Event.KEY, BukkitSyntaxInfos.Event.builder(EvtCustomBlockAction.class, "Custom Block Action")
                .supplier(EvtCustomBlockAction::new)
                .addEvent(CustomBlockEvent.class)
                .addDescription("Fires for everything that happens to a custom block's proxy block: break, explode, piston, burn and more.")
                .addExample("on custom block explode of \"table\":")
                .addPatterns(EvtCustomBlockAction.PATTERNS)
                .build());
    }
}
