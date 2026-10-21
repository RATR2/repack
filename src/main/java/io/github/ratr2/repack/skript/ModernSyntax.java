package io.github.ratr2.repack.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.aliases.ItemType;
import io.github.ratr2.repack.Repack;
import io.github.ratr2.repack.block.CustomBlockEvent;
import io.github.ratr2.repack.block.CustomBlockInteractEvent;
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
        registry.register(SyntaxRegistry.EFFECT, SyntaxInfo.builder(EffRegisterCustomBlock.class)
                .supplier(EffRegisterCustomBlock::new).addPatterns(EffRegisterCustomBlock.PATTERNS).build());

        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprCustomBlockId.class, String.class)
                .supplier(ExprCustomBlockId::new).addPatterns(ExprCustomBlockId.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprCustomBlockIds.class, String.class)
                .supplier(ExprCustomBlockIds::new).addPatterns(ExprCustomBlockIds.PATTERNS).build());
        registry.register(SyntaxRegistry.EXPRESSION, SyntaxInfo.Expression.builder(ExprCustomBlockItem.class, ItemType.class)
                .supplier(ExprCustomBlockItem::new).addPatterns(ExprCustomBlockItem.PATTERNS).build());

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
