package fi.dy.masa.servux.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * On this version a stack in a slot and the contents of a container component are both
 * plain {@link ItemStack}s, so these work on either - which is what walking a shulker box
 * full of bundles actually hands you.
 */
public class InventoryUtils
{
    public static boolean isShulkerBox(ItemStack stack)
    {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean shulkerBoxHasItems(ItemStack stack)
    {
        ItemContainerContents container = stack.getComponents().get(DataComponents.CONTAINER);

        if (container != null)
        {
            return container.nonEmptyItems().iterator().hasNext();
        }

        return false;
    }

    public static boolean isBundle(ItemStack stack)
    {
        return stack.getItem() instanceof BundleItem;
    }

    public static boolean bundleHasItems(ItemStack stack)
    {
        BundleContents contents = stack.getComponents().get(DataComponents.BUNDLE_CONTENTS);

        return contents != null && contents.isEmpty() == false;
    }
}
