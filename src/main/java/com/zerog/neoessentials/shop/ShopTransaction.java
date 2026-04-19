package com.zerog.neoessentials.shop;

import com.zerog.neoessentials.shop.model.ShopData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.sirgrantd.sg_economy.api.SGEconomyApi;
import net.sirgrantd.sg_economy.api.economy.EconomyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class ShopTransaction {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShopTransaction.class);

    private ShopTransaction() {}

    public enum ResultType { SUCCESS, NOT_ENOUGH_MONEY, NOT_ENOUGH_STOCK, NO_SPACE,
                             NO_CHEST, NO_ECONOMY_ACCOUNT, SHOP_DISABLED, ERROR }

    public static class TransactionResult {
        public final ResultType type;
        public final String message;
        public final BigDecimal price;
        public final int quantity;

        TransactionResult(ResultType type, String message, BigDecimal price, int quantity) {
            this.type = type; this.message = message;
            this.price = price; this.quantity = quantity;
        }
        public boolean isSuccess() { return type == ResultType.SUCCESS; }
    }

    private static TransactionResult ok(BigDecimal price, int qty) {
        return new TransactionResult(ResultType.SUCCESS, null, price, qty);
    }
    private static TransactionResult fail(ResultType type) {
        return new TransactionResult(type, type.name(), BigDecimal.ZERO, 0);
    }

    // ── BUY ───────────────────────────────────────────────────────────────────

    public static TransactionResult executeBuy(ServerPlayer buyer, ShopData shop, ServerLevel level) {
        if (!shop.canBuy()) return fail(ResultType.SHOP_DISABLED);

        EconomyProvider eco = SGEconomyApi.getSGEconomy();
        BigDecimal price = shop.buyPrice.setScale(2, RoundingMode.HALF_UP);
        double priceD = price.doubleValue();
        ItemStack template = resolveItem(shop.itemId);
        if (template.isEmpty()) return fail(ResultType.ERROR);
        ItemStack item = template.copyWithCount(shop.quantity);

        if (eco.getCurrency(buyer) < priceD) return fail(ResultType.NOT_ENOUGH_MONEY);

        if (!shop.isAdminShop()) {
            ChestBlockEntity chest = getChest(shop, level);
            if (chest == null) return fail(ResultType.NO_CHEST);
            if (countItems(chest, template) < shop.quantity) return fail(ResultType.NOT_ENOUGH_STOCK);
        }

        if (!hasSpace(buyer.getInventory(), item)) return fail(ResultType.NO_SPACE);

        eco.removeCurrency(buyer, priceD);

        if (!shop.isAdminShop()) {
            ChestBlockEntity chest = getChest(shop, level);
            if (chest == null) {
                eco.addCurrency(buyer, priceD); // rollback
                return fail(ResultType.NO_CHEST);
            }
            if (!removeItems(chest, template, shop.quantity)) {
                eco.addCurrency(buyer, priceD); // rollback
                return fail(ResultType.NOT_ENOUGH_STOCK);
            }
        }

        giveItems(buyer, item);

        if (!shop.isAdminShop() && shop.ownerUUID != null) {
            ServerPlayer owner = level.getServer().getPlayerList().getPlayer(shop.ownerUUID);
            if (owner != null) {
                eco.addCurrency(owner, priceD);
            } else {
                ShopPendingPayments.getInstance().add(shop.ownerUUID, priceD);
            }
        }

        LOGGER.debug("[ChestShop] BUY: {} bought {}x {} for {} from {}",
            buyer.getName().getString(), shop.quantity, shop.itemId, price, shop.ownerName);
        return ok(price, shop.quantity);
    }

    // ── SELL ──────────────────────────────────────────────────────────────────

    public static TransactionResult executeSell(ServerPlayer seller, ShopData shop, ServerLevel level) {
        if (!shop.canSell()) return fail(ResultType.SHOP_DISABLED);

        EconomyProvider eco = SGEconomyApi.getSGEconomy();
        BigDecimal price = shop.sellPrice.setScale(2, RoundingMode.HALF_UP);
        double priceD = price.doubleValue();
        ItemStack template = resolveItem(shop.itemId);
        if (template.isEmpty()) return fail(ResultType.ERROR);
        ItemStack item = template.copyWithCount(shop.quantity);

        if (countItems(seller.getInventory(), template) < shop.quantity) return fail(ResultType.NOT_ENOUGH_STOCK);

        ServerPlayer owner = null;
        if (!shop.isAdminShop() && shop.ownerUUID != null) {
            owner = level.getServer().getPlayerList().getPlayer(shop.ownerUUID);
            // Only check balance if owner is online; offline owners are charged on login
            if (owner != null && eco.getCurrency(owner) < priceD) return fail(ResultType.NOT_ENOUGH_MONEY);
        }

        if (!shop.isAdminShop()) {
            ChestBlockEntity chest = getChest(shop, level);
            if (chest == null) return fail(ResultType.NO_CHEST);
            if (!hasSpaceInContainer(chest, template, shop.quantity)) return fail(ResultType.NO_SPACE);
        }

        if (!removeItemsFromPlayer(seller, template, shop.quantity)) return fail(ResultType.NOT_ENOUGH_STOCK);

        if (!shop.isAdminShop() && shop.ownerUUID != null) {
            if (owner != null) {
                eco.removeCurrency(owner, priceD);
            } else {
                ShopPendingPayments.getInstance().deduct(shop.ownerUUID, priceD);
            }
        }

        if (!shop.isAdminShop()) {
            ChestBlockEntity chest = getChest(shop, level);
            if (chest != null) addItems(chest, template, shop.quantity);
        }

        eco.addCurrency(seller, priceD);

        LOGGER.debug("[ChestShop] SELL: {} sold {}x {} for {} to {}",
            seller.getName().getString(), shop.quantity, shop.itemId, price, shop.ownerName);
        return ok(price, shop.quantity);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static ItemStack resolveItem(String itemId) {
        try {
            ItemStack result = com.zerog.neoessentials.economy.worth.WorthManager.resolveItem(itemId);
            if (result != null && !result.isEmpty()) return result;
        } catch (Exception ignored) {}
        return ItemStack.EMPTY;
    }

    private static ChestBlockEntity getChest(ShopData shop, ServerLevel level) {
        if (!shop.hasChest) return null;
        BlockPos pos = shop.getChestPos();
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof ChestBlockEntity c ? c : null;
    }

    private static int countItems(Container container, ItemStack target) {
        int count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, target)) count += slot.getCount();
        }
        return count;
    }

    private static boolean removeItems(Container container, ItemStack target, int amount) {
        int toRemove = amount;
        for (int i = 0; i < container.getContainerSize() && toRemove > 0; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, target)) {
                int take = Math.min(slot.getCount(), toRemove);
                slot.shrink(take);
                toRemove -= take;
                container.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
            }
        }
        if (container instanceof BlockEntity be) be.setChanged();
        return toRemove == 0;
    }

    private static boolean removeItemsFromPlayer(ServerPlayer player, ItemStack target, int amount) {
        return removeItems(player.getInventory(), target, amount);
    }

    private static void giveItems(ServerPlayer player, ItemStack item) {
        ItemStack copy = item.copy();
        if (!player.getInventory().add(copy)) player.drop(copy, false);
    }

    private static void addItems(Container container, ItemStack target, int amount) {
        int toAdd = amount;
        for (int i = 0; i < container.getContainerSize() && toAdd > 0; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && ItemStack.isSameItemSameComponents(slot, target)) {
                int space = slot.getMaxStackSize() - slot.getCount();
                int add = Math.min(space, toAdd);
                slot.grow(add);
                toAdd -= add;
                container.setItem(i, slot);
            }
        }
        for (int i = 0; i < container.getContainerSize() && toAdd > 0; i++) {
            if (container.getItem(i).isEmpty()) {
                int stackAmt = Math.min(toAdd, target.getMaxStackSize());
                container.setItem(i, target.copyWithCount(stackAmt));
                toAdd -= stackAmt;
            }
        }
        if (container instanceof BlockEntity be) be.setChanged();
    }

    private static boolean hasSpaceInContainer(Container container, ItemStack target, int amount) {
        int canFit = 0;
        for (int i = 0; i < container.getContainerSize(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) canFit += target.getMaxStackSize();
            else if (ItemStack.isSameItemSameComponents(slot, target)) canFit += slot.getMaxStackSize() - slot.getCount();
            if (canFit >= amount) return true;
        }
        return false;
    }

    private static boolean hasSpace(net.minecraft.world.entity.player.Inventory inv, ItemStack item) {
        return hasSpaceInContainer(inv, item, item.getCount());
    }
}
