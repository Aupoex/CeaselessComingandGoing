package euphy.upo.ceaselesscag.registry;

import euphy.upo.ceaselesscag.CeaselessCaG;
import euphy.upo.ceaselesscag.item.PathfinderToolItem;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ItemRegistry {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(BuiltInRegistries.ITEM, CeaselessCaG.MODID);

    public static final DeferredHolder<Item, Item> PATHFINDER_TOOL = ITEMS.register("pathfinder_tool",
            () -> new PathfinderToolItem(new Item.Properties())
    );

}