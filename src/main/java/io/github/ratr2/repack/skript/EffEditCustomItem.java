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
import io.github.ratr2.repack.edit.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.event.Event;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Creates, registers or changes a custom item: its name, lore, glint, base item, model, or any option of its item file.
 */
@Name("Edit Custom Item")
@Description({"Creates, registers or changes a custom item: its name, lore, glint, base item, model, or any option of its item file.", "`create` writes plugins/Repack/items/<id>.yml. `register` only lasts until the next reload."})
@Examples({"create custom item \"ruby\" with base \"EMERALD\"", "set custom item \"ruby\"'s name to \"&cRuby\"", "set custom item \"ruby\"'s lore to \"&7A shiny stone\" and \"&7found deep down\"", "set custom item \"ruby\"'s glint to true", "clear custom item \"ruby\"'s lore"})
@Since("1.0.1")
public class EffEditCustomItem extends Effect {

    static final String[] PATTERNS = {
            "create [a] custom item %string% [(with|using) base %-string%] [(and|with) model %-string%]",
            "register [a] custom item %string% (with|using) base %string% [(and|with) model %-string%]",
            "unregister [a] custom item %string%",
            "set custom item %string%'s name to %string%",
            "set custom item %string%'s lore to %strings%",
            "set custom item %string%'s glint to %boolean%",
            "set custom item %string%'s base to %string%",
            "set custom item %string%'s model to %string%",
            "set custom item %string%'s option %string% to %objects%",
            "clear custom item %string%'s (:name|:lore|:model)",
    };

    private int pattern;
    private Expression<String> id;
    private @Nullable Expression<?> first;
    private @Nullable Expression<?> second;
    private String cleared = "";

    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, ParseResult parseResult) {
        pattern = matchedPattern;
        id = castString(exprs[0]);
        if (exprs.length > 1) first = exprs[1];
        if (exprs.length > 2) second = exprs[2];
        if (matchedPattern == 9) {
            cleared = parseResult.hasTag("name") ? "name" : parseResult.hasTag("lore") ? "lore" : "model";
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static Expression<String> castString(Expression<?> expression) {
        return (Expression<String>) expression;
    }

    private @Nullable String text(@Nullable Expression<?> expression, Event event) {
        Object value = expression == null ? null : expression.getSingle(event);
        return value == null ? null : String.valueOf(value);
    }

    @Override
    protected void execute(Event event) {
        String item = id.getSingle(event);
        if (item == null) return;
        String error;
        switch (pattern) {
            case 0 -> error = create(item, event);
            case 1 -> {
                String base = text(first, event);
                error = base == null ? "no base item" : RepackAPI.registerItem(item, base, text(second, event));
            }
            case 2 -> error = RepackAPI.unregisterItem(item) ? null : "there is no such item";
            case 3 -> error = RepackAPI.editItem(item).name(text(first, event)).save();
            case 4 -> error = RepackAPI.editItem(item).lore(lines(event)).save();
            case 5 -> error = RepackAPI.editItem(item).glint(first != null && Boolean.TRUE.equals(first.getSingle(event))).save();
            case 6 -> error = setBase(RepackAPI.editItem(item), text(first, event));
            case 7 -> error = RepackAPI.editItem(item).model(text(first, event)).save();
            case 8 -> error = setOption(item, event);
            default -> error = switch (cleared) {
                case "name" -> RepackAPI.editItem(item).name(null).save();
                case "lore" -> RepackAPI.editItem(item).lore().save();
                default -> RepackAPI.editItem(item).model(null).save();
            };
        }
        if (error != null) Bukkit.getLogger().warning("[Repack] custom item '" + item + "': " + error);
    }

    private @Nullable String create(String item, Event event) {
        ItemBuilder builder = RepackAPI.createItem(item);
        String base = text(first, event);
        String model = text(second, event);
        if (base != null) {
            String problem = setBase(builder, base);
            if (problem != null) return problem;
        }
        if (model != null) builder.model(model);
        return builder.save();
    }

    private @Nullable String setBase(ItemBuilder builder, @Nullable String name) {
        Material material = name == null ? null : Material.matchMaterial(name);
        if (material == null || !material.isItem()) return "'" + name + "' is not an item";
        return builder.base(material).save();
    }

    private String[] lines(Event event) {
        List<String> all = new ArrayList<>();
        if (first != null) for (Object line : first.getArray(event)) all.add(String.valueOf(line));
        return all.toArray(String[]::new);
    }

    private @Nullable String setOption(String item, Event event) {
        String path = text(first, event);
        if (path == null || second == null) return "no option";
        Object[] all = second.getArray(event);
        Object value;
        if (all.length == 1) {
            value = plain(all[0]);
        } else {
            List<Object> list = new ArrayList<>();
            for (Object entry : all) list.add(plain(entry));
            value = list;
        }
        return RepackAPI.editItem(item).set(path, value).save();
    }

    private static Object plain(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof String) return value;
        return String.valueOf(value);
    }

    @Override
    public String toString(@Nullable Event event, boolean debug) {
        return "edit custom item " + id.toString(event, debug);
    }
}
