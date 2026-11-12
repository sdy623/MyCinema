package local.cinemamanager.access;

import java.util.*;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/** Dimension-local authority. It is never sent in block updates or copied to a dropped reader. */
public final class StaffAccessRegistry extends SavedData {
    public static final int MAX_DOORS = 2048;
    public record Leaf(long pos, String shape) { public Leaf { Objects.requireNonNull(shape); } }
    public static final class Door {
        public final UUID token, reader;
        public final long readerPos;
        public UUID controller, controllerOwner;
        public long controllerPos;
        public final List<Leaf> leaves;
        private long until;
        public Door(UUID token, UUID reader, long readerPos, List<Leaf> leaves) {
            this(token,reader,readerPos,leaves,null,null,0);
        }
        public Door(UUID token, UUID reader, long readerPos, List<Leaf> leaves, UUID controller, UUID controllerOwner, long controllerPos) {
            this.token=Objects.requireNonNull(token); this.reader=Objects.requireNonNull(reader); this.readerPos=readerPos;
            this.controller=controller;this.controllerOwner=controllerOwner;this.controllerPos=controllerPos;
            this.leaves=List.copyOf(leaves);
            if (leaves.isEmpty() || leaves.size()>2) throw new IllegalArgumentException("mycinema.access.invalid");
        }
        public long base() { return leaves.getFirst().pos(); }
        public boolean held(long now) { return until > now && until - now <= 100; }
    }
    public final StaffGrantBook cards = new StaffGrantBook();
    public final TabletAccessBook tablets = new TabletAccessBook();
    private final Map<Long, Door> doors = new HashMap<>();
    public static StaffAccessRegistry get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(new Factory<>(StaffAccessRegistry::new, StaffAccessRegistry::load), "mycinema_staff_access");
    }
    public Door door(long base) { return doors.get(base); }
    public Collection<Door> allDoors() { return List.copyOf(new HashSet<>(doors.values())); }
    public boolean captureScope(Door door,UUID controller,UUID owner,long pos) {
        if(door==null||doors.get(door.base())!=door||door.controller!=null||controller==null||owner==null)return false;
        door.controller=controller;door.controllerOwner=owner;door.controllerPos=pos;setDirty();return true;
    }
    public void bind(Door door) {
        if (doors.size()+door.leaves.stream().filter(l -> !doors.containsKey(l.pos())).count() > MAX_DOORS)
            throw new IllegalArgumentException("mycinema.access.limit");
        for (Leaf leaf : door.leaves) invalidate(leaf.pos());
        for (Leaf leaf : door.leaves) doors.put(leaf.pos(), door);
        setDirty();
    }
    public void invalidate(long base) {
        Door previous=doors.get(base);
        if (previous == null) return;
        doors.values().removeIf(d -> d == previous);
        // Employee grants belong to the cinema, not a particular door installation.
        // A surviving double-door leaf still needs inside egress. It gets a fresh orphan identity:
        // no old card or reader binding can authorize entry, and no removed leaf is resurrected.
        List<Leaf> surviving=previous.leaves.stream().filter(l -> l.pos()!=base).toList();
        if(!surviving.isEmpty()){
            Door orphan=new Door(UUID.randomUUID(),previous.reader,previous.readerPos,surviving,previous.controller,previous.controllerOwner,previous.controllerPos);
            for(Leaf leaf:surviving)doors.put(leaf.pos(),orphan);
        }
        setDirty();
    }
    public void pulse(Door door, long now) { door.until=now + 60; setDirty(); }
    public static StaffAccessRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        StaffAccessRegistry result=new StaffAccessRegistry();
        Set<Long> conflicted=new HashSet<>();
        for (Tag raw : tag.getList("doors", Tag.TAG_COMPOUND)) {
            if (result.doors.size()>=MAX_DOORS) break;
            try {
                CompoundTag d=(CompoundTag)raw; List<Leaf> leaves=new ArrayList<>();
                for (Tag item : d.getList("leaves", Tag.TAG_COMPOUND)) {
                    CompoundTag l=(CompoundTag)item; String shape=l.getString("shape");
                    if (!l.contains("pos",Tag.TAG_LONG) || shape.isBlank() || shape.length()>256) throw new IllegalArgumentException();
                    leaves.add(new Leaf(l.getLong("pos"),shape));
                }
                Door door=new Door(d.getUUID("token"),d.getUUID("reader"),d.getLong("readerPos"),leaves);
                if(d.hasUUID("controller")&&d.hasUUID("controllerOwner")&&d.contains("controllerPos",Tag.TAG_LONG)){
                    door.controller=d.getUUID("controller");door.controllerOwner=d.getUUID("controllerOwner");door.controllerPos=d.getLong("controllerPos");
                }
                if (leaves.stream().map(Leaf::pos).distinct().count()!=leaves.size()) continue;
                boolean overlap=leaves.stream().anyMatch(l -> result.doors.containsKey(l.pos()) || conflicted.contains(l.pos()));
                if (overlap) {
                    for (Leaf l : leaves) { Door old=result.doors.get(l.pos()); if(old!=null) { for(Leaf previous:old.leaves)conflicted.add(previous.pos()); result.doors.values().removeIf(v->v==old); } conflicted.add(l.pos()); }
                    continue;
                }
                if(result.doors.size()+leaves.size()>MAX_DOORS)continue;
                // Opening leases are deliberately not restored: restart/chunk reload closes unless redstone holds.
                for (Leaf l:leaves) result.doors.put(l.pos(),door);
            } catch (RuntimeException malformed) { /* malformed entries confer no authority */ }
        }
        List<StaffGrantBook.Grant> grants=new ArrayList<>();
        for (Tag raw : tag.getList("cards",Tag.TAG_COMPOUND)) {
            if(grants.size()>=StaffGrantBook.MAX_GRANTS*2)break;
            try {
                CompoundTag g=(CompoundTag)raw;
                StaffGrantBook.Scope s=new StaffGrantBook.Scope(g.getUUID("controller"),g.getUUID("controllerOwner"),g.getLong("controllerPos"),g.getUUID("reader"),g.getUUID("readerOwner"),g.getLong("readerPos"),g.getUUID("door"),g.getLong("doorPos"),g.getUUID("revision"));
                grants.add(new StaffGrantBook.Grant(g.getUUID("card"),g.getUUID("player"),g.getUUID("issuer"),s,StaffRole.valueOf(g.getString("role"))));
            }catch(RuntimeException malformed){ /* fail closed */ }
        }
        result.cards.restore(grants);
        List<TabletAccessBook.Grant> tablets=new ArrayList<>();
        for(Tag raw:tag.getList("tablets",Tag.TAG_COMPOUND)){
            if(tablets.size()>=TabletAccessBook.MAX_GRANTS*2)break;
            try{CompoundTag t=(CompoundTag)raw;if(!t.contains("controllerPos",Tag.TAG_LONG) || (t.contains("version") && (!t.contains("version",Tag.TAG_INT) || t.getInt("version")!=TabletAccessBook.CURRENT_VERSION)))continue;
                tablets.add(new TabletAccessBook.Grant(t.getUUID("token"),t.getUUID("player"),new TabletAccessBook.Scope(t.getUUID("controller"),t.getUUID("owner"),t.getLong("controllerPos"))));
            }catch(RuntimeException malformed){ /* missing or malformed activation confers no authority */ }
        }
        result.tablets.restore(tablets);return result;
    }
    @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag savedDoors=new ListTag();
        for(Door d:new HashSet<>(doors.values())) {
            CompoundTag value=new CompoundTag(); value.putUUID("token",d.token);value.putUUID("reader",d.reader);value.putLong("readerPos",d.readerPos);
            if(d.controller!=null&&d.controllerOwner!=null){value.putUUID("controller",d.controller);value.putUUID("controllerOwner",d.controllerOwner);value.putLong("controllerPos",d.controllerPos);}
            ListTag leaves=new ListTag();for(Leaf l:d.leaves){CompoundTag leaf=new CompoundTag();leaf.putLong("pos",l.pos());leaf.putString("shape",l.shape());leaves.add(leaf);}value.put("leaves",leaves);savedDoors.add(value);
        }
        tag.put("doors",savedDoors);ListTag savedCards=new ListTag();
        for(StaffGrantBook.Grant g:cards.all()) {
            CompoundTag c=new CompoundTag();var s=g.scope();c.putUUID("card",g.card());c.putUUID("player",g.player());c.putUUID("issuer",g.issuer());c.putString("role",g.role().name());
            c.putUUID("controller",s.controller());c.putUUID("controllerOwner",s.controllerOwner());c.putLong("controllerPos",s.controllerPos());c.putUUID("reader",s.reader());c.putUUID("readerOwner",s.readerOwner());c.putLong("readerPos",s.readerPos());c.putUUID("door",s.door());c.putLong("doorPos",s.doorPos());c.putUUID("revision",s.revision());savedCards.add(c);
        }
        tag.put("cards",savedCards);ListTag savedTablets=new ListTag();
        for(var grant:tablets.all()){
            CompoundTag t=new CompoundTag();t.putInt("version",TabletAccessBook.CURRENT_VERSION);t.putUUID("token",grant.token());t.putUUID("player",grant.player());t.putUUID("controller",grant.scope().controller());t.putUUID("owner",grant.scope().owner());t.putLong("controllerPos",grant.scope().pos());savedTablets.add(t);
        }
        tag.put("tablets",savedTablets);return tag;
    }
}
