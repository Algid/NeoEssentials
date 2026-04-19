package com.zerog.neoessentials.shop;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.zerog.neoessentials.util.ResourceUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.sirgrantd.sg_economy.api.SGEconomyApi;
import net.sirgrantd.sg_economy.api.economy.EconomyProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks net balance deltas for shop owners who were offline during transactions.
 * Positive delta = owed to owner (buy payouts). Negative delta = owed by owner (sell charges).
 * Applied on login via deposit/withdraw, with withdrawals capped at current balance.
 */
public class ShopPendingPayments {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShopPendingPayments.class);
    private static final ShopPendingPayments INSTANCE = new ShopPendingPayments();

    private final File file = ResourceUtil.getDataFile("pending_shop_payments.json");
    private final Gson gson = new Gson();
    private final ConcurrentHashMap<UUID, Double> deltas = new ConcurrentHashMap<>();

    private ShopPendingPayments() {}

    public static ShopPendingPayments getInstance() { return INSTANCE; }

    public void initialize() {
        if (!file.exists()) return;
        try (FileReader reader = new FileReader(file)) {
            Type type = new TypeToken<Map<String, Double>>(){}.getType();
            Map<String, Double> data = gson.fromJson(reader, type);
            if (data != null) data.forEach((k, v) -> deltas.put(UUID.fromString(k), v));
        } catch (Exception e) {
            LOGGER.error("Failed to load pending shop payments", e);
        }
    }

    public void shutdown() { save(); }

    /** Owner earned money from a buy while offline. */
    public void add(UUID ownerUUID, double amount) {
        deltas.merge(ownerUUID, amount, Double::sum);
        save();
    }

    /** Owner owes money for a sell while offline. */
    public void deduct(UUID ownerUUID, double amount) {
        deltas.merge(ownerUUID, -amount, Double::sum);
        save();
    }

    /** Apply net delta to player on login. Withdrawals are capped at current balance. */
    public void drain(ServerPlayer player) {
        Double delta = deltas.remove(player.getUUID());
        if (delta == null || delta == 0) return;

        EconomyProvider eco = SGEconomyApi.getSGEconomy();
        if (delta > 0) {
            eco.addCurrency(player, delta);
            player.sendSystemMessage(Component.literal(String.format(
                "§aYou received §e%.2f§a from your shop(s) while offline.", delta)));
        } else {
            double owed = Math.abs(delta);
            double current = eco.getCurrency(player);
            double toWithdraw = Math.min(owed, current);
            if (toWithdraw > 0) {
                eco.removeCurrency(player, toWithdraw);
                player.sendSystemMessage(Component.literal(String.format(
                    "§e%.2f§c was collected for sales made at your shop(s) while offline.", toWithdraw)));
            }
        }

        save();
    }

    private void save() {
        try {
            file.getParentFile().mkdirs();
            File tmp = new File(file.getAbsolutePath() + ".tmp");
            try (FileWriter writer = new FileWriter(tmp)) {
                Map<String, Double> data = new HashMap<>();
                deltas.forEach((k, v) -> data.put(k.toString(), v));
                gson.toJson(data, writer);
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOGGER.error("Failed to save pending shop payments", e);
        }
    }
}
