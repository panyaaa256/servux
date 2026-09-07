package fi.dy.masa.servux.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemInstance;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/**
 * These take {@link ItemInstance} rather than {@code ItemStack} so that they also work on
 * the {@code ItemStackTemplate}s inside a container component - which is what walking a
 * shulker box full of bundles actually hands you.
 */
public class InventoryUtils
{
    public static boolean isShulkerBox(ItemInstance item)
    {
        return item.typeHolder().value() instanceof BlockItem blockItem && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    public static boolean shulkerBoxHasItems(ItemInstance item)
    {
        ItemContainerContents container = item.get(DataComponents.CONTAINER);

        if (container != null)
        {
            return container.nonEmptyItems().iterator().hasNext();
        }

        return false;
    }

    public static boolean isBundle(ItemInstance item)
    {
        return item.typeHolder().value() instanceof BundleItem;
    }

    public static boolean bundleHasItems(ItemInstance item)
    {
        BundleContents contents = item.get(DataComponents.BUNDLE_CONTENTS);

        return contents != null && contents.isEmpty() == false;
    }
}
