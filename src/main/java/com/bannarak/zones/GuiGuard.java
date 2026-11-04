package com.bannarak.zones;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.InventoryHolder;

/**
 * One guard for every menu this plugin opens.
 *
 * <p>Each menu cancels clicks, but a drag is a different event and none of
 * them cancelled it. Anyone could hold a stack and drag it across the empty
 * slots of a menu page; the items went in, and vanished when the page closed.
 *
 * <p>Rather than repeat the same handler in thirteen classes and forget it in
 * the fourteenth, this catches any inventory whose holder belongs to us.
 */
public class GuiGuard implements Listener {

    private static final String OURS = "com.bannarak.zones.";

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        InventoryHolder holder = e.getInventory().getHolder();
        if (holder == null || !holder.getClass().getName().startsWith(OURS)) return;

        // Dragging purely inside the player's own inventory is none of our
        // business; only a drag that touches the menu itself is.
        int top = e.getView().getTopInventory().getSize();
        for (int raw : e.getRawSlots()) {
            if (raw < top) { e.setCancelled(true); return; }
        }
    }
}
