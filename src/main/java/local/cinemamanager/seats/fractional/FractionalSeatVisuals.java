package local.cinemamanager.seats.fractional;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Display;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;

/** Reversible item-only change on the existing display; entity UUID, position and transform stay intact. */
final class FractionalSeatVisuals {
    private static final String BACKUP = "mycinema_fractional_visual_backup";
    private static ItemStack selected() { return new ItemStack(FractionalSeatModule.DISPLAY_ITEM.get()); }
    private static ItemStack authored() {
        ItemStack stack = new ItemStack(Items.PAPER); stack.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(1001)); return stack;
    }
    static boolean hasProtectionEvidence(ServerLevel level, FractionalSeatLayout.Seat seat) {
        if (FractionalSeatLayout.get(level).isOriginal368()) {
            for (var entity : level.getAllEntities()) if (entity instanceof Display.ItemDisplay display
                && Original368Identity.matches(entity,seat,"visual",FractionalSeatLayout.get(level).version) && display.getSlot(0).get().is(FractionalSeatModule.DISPLAY_ITEM.get())) return true;
            return false;
        }
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Display.ItemDisplay display && entity.getTags().contains("os_visual")
                && entity.getTags().contains("os_" + seat.label())
                && (display.getPersistentData().contains(BACKUP) || display.getSlot(0).get().is(FractionalSeatModule.DISPLAY_ITEM.get()))) return true;
        }
        return false;
    }
    static void verify(ServerLevel level, FractionalSeatLayout.Seat seat, boolean installed) {
        Display.ItemDisplay display = FractionalSeatIntegration.entities(level, seat).visual();
        ItemStack stack = display.getSlot(0).get();
        if (FractionalSeatLayout.get(level).isOriginal368()) {
            if (!ItemStack.matches(stack,selected()) || display.getPersistentData().contains(BACKUP)) throw bad(seat,"original368 selected visual/backup contract mismatch");
            return;
        }
        if (!installed) {
            if (display.getPersistentData().contains(BACKUP) || !ItemStack.matches(stack, authored())) throw bad(seat, "original display item mismatch");
        } else {
            if (!ItemStack.matches(stack, selected())) throw bad(seat, "selected display item was changed");
            original(level, display, seat);
        }
    }
    static void install(ServerLevel level, FractionalSeatLayout.Seat seat) {
        verify(level, seat, false);
        if (FractionalSeatLayout.get(level).isOriginal368()) return;
        Display.ItemDisplay display = FractionalSeatIntegration.entities(level, seat).visual();
        ItemStack original = display.getSlot(0).get().copy(), selected = selected();
        CompoundTag backup = new CompoundTag(); backup.putUUID("seat_uid", seat.uid()); backup.putString("model", FractionalSeatLayout.MODEL);
        backup.put("original", original.save(level.registryAccess())); display.getPersistentData().put(BACKUP, backup);
        try {
            if (!display.getSlot(0).set(selected) || !ItemStack.matches(display.getSlot(0).get(), selected)) throw bad(seat, "display item write failed");
        } catch (RuntimeException failed) {
            // Never erase an item another callback installed. Keep its backup for manual recovery.
            if (ItemStack.matches(display.getSlot(0).get(), selected)) display.getSlot(0).set(original);
            if (ItemStack.matches(display.getSlot(0).get(), original)) display.getPersistentData().remove(BACKUP);
            throw failed;
        }
    }
    static void uninstall(ServerLevel level, FractionalSeatLayout.Seat seat) {
        verify(level, seat, true);
        if (FractionalSeatLayout.get(level).isOriginal368()) return;
        Display.ItemDisplay display = FractionalSeatIntegration.entities(level, seat).visual();
        ItemStack original = original(level, display, seat);
        if (!display.getSlot(0).set(original.copy()) || !ItemStack.matches(display.getSlot(0).get(), original)) throw bad(seat, "original item restoration failed; backup retained");
        display.getPersistentData().remove(BACKUP);
    }
    record Snapshot(java.util.UUID entity, ItemStack item, CompoundTag backup) {}
    /** Accepts only the exact original, installed, or known crash-staged original+backup states. */
    static Snapshot capture(ServerLevel level, FractionalSeatLayout.Seat seat) {
        Display.ItemDisplay display = FractionalSeatIntegration.entities(level, seat).visual();
        ItemStack item = display.getSlot(0).get().copy();
        CompoundTag backup = display.getPersistentData().contains(BACKUP) ? display.getPersistentData().getCompound(BACKUP).copy() : null;
        if (FractionalSeatLayout.get(level).isOriginal368()) {
            verify(level,seat,true); return new Snapshot(display.getUUID(),item,null);
        }
        if (backup == null) {
            if (!ItemStack.matches(item, authored())) throw bad(seat, "foreign visual item without backup");
        } else {
            original(level, display, seat);
            if (!ItemStack.matches(item, authored()) && !ItemStack.matches(item, selected())) throw bad(seat, "foreign visual item with backup");
        }
        return new Snapshot(display.getUUID(), item, backup);
    }
    static void reconcile(ServerLevel level, FractionalSeatLayout.Seat seat, boolean install) {
        Snapshot before = capture(level, seat);
        if (FractionalSeatLayout.get(level).isOriginal368()) return;
        Display.ItemDisplay display = FractionalSeatIntegration.entities(level, seat).visual();
        if (install) {
            if (ItemStack.matches(before.item(), selected())) return;
            if (before.backup() == null) { install(level, seat); return; }
            if (!display.getSlot(0).set(selected()) || !ItemStack.matches(display.getSlot(0).get(), selected())) throw bad(seat, "staged visual installation failed");
        } else {
            if (before.backup() == null) return;
            if (ItemStack.matches(before.item(), authored())) { display.getPersistentData().remove(BACKUP); return; }
            uninstall(level, seat);
        }
    }
    static void restore(ServerLevel level, FractionalSeatLayout.Seat seat, Snapshot before, Snapshot after) {
        Snapshot current = capture(level, seat);
        if (!same(current, after)) throw bad(seat, "visual rollback conflict preserved");
        Display.ItemDisplay display = FractionalSeatIntegration.entities(level, seat).visual();
        if (before.backup() != null) display.getPersistentData().put(BACKUP, before.backup().copy());
        if (!display.getSlot(0).set(before.item().copy()) || !ItemStack.matches(display.getSlot(0).get(), before.item())) throw bad(seat, "visual rollback write failed");
        if (before.backup() == null) display.getPersistentData().remove(BACKUP);
        if (!same(capture(level, seat), before)) throw bad(seat, "visual rollback readback failed");
    }
    static boolean same(Snapshot a, Snapshot b) {
        return a.entity().equals(b.entity()) && ItemStack.matches(a.item(), b.item()) && java.util.Objects.equals(a.backup(), b.backup());
    }
    private static ItemStack original(ServerLevel level, Display.ItemDisplay display, FractionalSeatLayout.Seat seat) {
        if (!display.getPersistentData().contains(BACKUP)) throw bad(seat, "original display backup missing");
        CompoundTag backup = display.getPersistentData().getCompound(BACKUP);
        if (!backup.hasUUID("seat_uid") || !seat.uid().equals(backup.getUUID("seat_uid")) || !FractionalSeatLayout.MODEL.equals(backup.getString("model")))
            throw bad(seat, "display backup identity mismatch");
        ItemStack stack = ItemStack.parse(level.registryAccess(), backup.getCompound("original")).orElseThrow(() -> bad(seat, "invalid original display backup"));
        if (!ItemStack.matches(stack, authored())) throw bad(seat, "original display backup is not the verified paper/model1001 item");
        return stack;
    }
    private static IllegalStateException bad(FractionalSeatLayout.Seat seat, String text) { return new IllegalStateException(text + " for " + seat.label()); }
    private FractionalSeatVisuals() {}
}
