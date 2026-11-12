package local.cinemamanager;

import local.cinemamanager.panels.*;
import local.cinemamanager.operations.*;
import local.cinemamanager.model.ManagerState;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

public final class InfoTile extends OwnedTile {
    public BlockPos manager;
    public UUID managerIdentity;
    public String hallId="";
    public PanelSettings panel=PanelSettings.DEFAULT;
    public PanelScope scope=PanelScope.HALL;
    public TerminalRoleSettings terminalRole=TerminalRoleSettings.LEGACY;
    private Tag invalidTerminalRole;
    private boolean publicInvalidTerminalRole;
    public PublicOperationsSnapshot operationsSnapshot=PublicOperationsSnapshot.EMPTY;
    public boolean invalidTerminalRole(){return invalidTerminalRole!=null||publicInvalidTerminalRole;}
    public boolean isOperations(){return !invalidTerminalRole()&&terminalRole.role()==TerminalRoleSettings.Role.OPERATIONS&&terminalRole.validFor(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).toString(),scope);}
    /** Public seat-map callers must require this on the clicked tile and its current group root. */
    public boolean isAudienceInformation(){return !invalidTerminalRole()&&terminalRole.role()==TerminalRoleSettings.Role.AUDIENCE&&terminalRole.validFor(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).toString(),scope);}
    public local.cinemamanager.advertising.AdvertisingGeometry advertising=local.cinemamanager.advertising.AdvertisingGeometry.DEFAULT;
    public local.cinemamanager.advertising.AdvertisingShape advertisingShape=local.cinemamanager.advertising.AdvertisingShape.RECTANGLE;
    public local.cinemamanager.advertising.ScreenMode screenMode=local.cinemamanager.advertising.ScreenMode.PICTURE;
    public local.cinemamanager.mirror.CinemaMirrors.BindingRef mirror;
    public UUID mirrorBoardOwner;
    public local.cinemamanager.mirror.CinemaMirrors.BindingRef publishedMirror;
    public int assembledUnits=1;
    public UUID panelGroup=UUID.randomUUID();
    public long panelRevision;
    /** Owner-editor diagnostics only. Never included in block update tags or guest responses. */
    public List<String> panelFields=List.of();
    public PublicPanelSnapshot publicSnapshot=PublicPanelSnapshot.EMPTY;
    public String publishedPoster="";
    public String publishedPosterToken="";
    private final PosterPublicationState posterPublication=new PosterPublicationState();
    private PosterGrant posterGrant;
    private boolean publishedAsRoot;
    private String publishedVisualGroup="";
    public InfoTile(BlockPos p,BlockState s){super(CinemaManager.INFO_TILE.get(),p,s);}
    public boolean isAdvertising(){return getBlockState().getBlock() instanceof local.cinemamanager.advertising.AdvertisingBlock;}
    public double panelDistanceSquared(net.minecraft.world.entity.player.Player actor){return isAdvertising()?advertising.distanceSquared(worldPosition,getBlockState().getValue(InfoBlock.FACING),getBlockState().getValue(local.cinemamanager.advertising.AdvertisingBlock.FLOOR),actor.position()):actor.distanceToSqr(worldPosition.getCenter());}
    @Override public boolean permits(net.minecraft.world.entity.player.Player actor,double range){return !isAdvertising()?super.permits(actor,range):super.permits(actor,Double.POSITIVE_INFINITY)&&panelDistanceSquared(actor)<=range*range;}
    @Override public boolean onlyOpCanSetNbt(){return true;}
    /** Item NBT is not a saved-world authority, even when a creative item carries valid old IDs. */
    public void initializePlacement(UUID placingOwner){
        if(level==null||level.isClientSide)return;
        owner=placingOwner;identity=UUID.randomUUID();terminalRole=TerminalRoleSettings.LEGACY;invalidTerminalRole=null;publicInvalidTerminalRole=false;operationsSnapshot=PublicOperationsSnapshot.EMPTY;manager=null;managerIdentity=null;hallId="";mirror=null;mirrorBoardOwner=null;publishedMirror=null;assembledUnits=1;advertisingShape=local.cinemamanager.advertising.AdvertisingShape.RECTANGLE;screenMode=local.cinemamanager.advertising.ScreenMode.PICTURE;posterGrant=null;publishedPoster="";publishedPosterToken="";posterPublication.clear();
    }
    /** Vanilla creative pick/copy must never send disk-only authorship or control bindings. */
    @Override public void removeComponentsFromTag(CompoundTag tag){
        super.removeComponentsFromTag(tag);
        for(String key:new HashSet<>(tag.getAllKeys()))tag.remove(key);
        tag.put("panel_settings",panel.publicOnly(scope,approvedPoster()).save());
        tag.putString("panel_scope",scope.name());
    }
    public ManagerTile controller(){return level!=null&&manager!=null&&level.hasChunkAt(manager)&&level.getBlockEntity(manager) instanceof ManagerTile t&&t.identity.equals(managerIdentity)?t:null;}
    public void interact(ServerPlayer actor,boolean edit){
        if(edit){if(permits(actor,8))PanelNetwork.open(actor,this,true);else actor.displayClientMessage(net.minecraft.network.chat.Component.translatable("mycinema.panel.permission"),true);}
        else PanelNetwork.openGuest(actor,this,true);
    }
    public void joinAdjacent(){
        if(level==null||level.isClientSide||owner==null)return;
        InfoTile source=PanelGroup.placementSource(this);
        if(source==null)return;
        panelGroup=source.panelGroup;panel=source.panel;scope=source.scope;panelRevision=source.panelRevision;
        terminalRole=source.terminalRole;invalidTerminalRole=source.invalidTerminalRole==null?null:source.invalidTerminalRole.copy();publicInvalidTerminalRole=false;
        manager=source.manager;managerIdentity=source.managerIdentity;hallId=source.hallId;
        posterGrant=source.posterGrant==null?null:source.posterGrant.extend(source,this);
    }
    private PanelGroup authorize(ServerPlayer actor,String signature){
        if(!permits(actor,8))throw new IllegalArgumentException("mycinema.panel.permission");
        PanelGroup group=PanelGroup.of(this);
        if(signature!=null&&!group.signature().equals(signature))throw new IllegalArgumentException("mycinema.panel.stale");
        for(InfoTile member:group.members())if(!member.permits(actor,Double.POSITIVE_INFINITY))throw new IllegalArgumentException("mycinema.panel.permission");
        return group;
    }
    public void configure(ServerPlayer actor,String signature,PanelSettings settings){configure(actor,signature,settings,scope);}
    public void configure(ServerPlayer actor,String signature,PanelSettings settings,PanelScope newScope){configure(actor,signature,settings,newScope,terminalRole);}
    public void configure(ServerPlayer actor,String signature,PanelSettings settings,PanelScope newScope,TerminalRoleSettings role){
        if(isAdvertising())local.cinemamanager.advertising.AdvertisingLifecycle.replaceForPoster(this,actor,signature,settings==null?null:settings.poster(),()->configurePrepared(actor,signature,settings,newScope,role));
        else configurePrepared(actor,signature,settings,newScope,role);
    }
    private void configurePrepared(ServerPlayer actor,String signature,PanelSettings settings,PanelScope newScope,TerminalRoleSettings role){
        PanelGroup group=authorize(actor,signature);
        InfoTile root=group.root();
        if(role==null||isAdvertising()&&!role.equals(TerminalRoleSettings.LEGACY)||!isAdvertising()&&!role.validFor(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).toString(),newScope))throw new IllegalArgumentException("mycinema.panel.invalid_role");
        if(role.role()==TerminalRoleSettings.Role.OPERATIONS){
            ManagerTile controller=root.controller();
            if(root.owner==null||!root.owner.equals(actor.getUUID())||controller!=null&&(controller.owner==null||!controller.owner.equals(actor.getUUID())||!controller.permits(actor,Double.POSITIVE_INFINITY)))throw new IllegalArgumentException("mycinema.panel.permission");
        }
        if(settings==null||newScope==null||isAdvertising()&&screenMode!=local.cinemamanager.advertising.ScreenMode.INFORMATION&&newScope!=PanelScope.MANUAL)throw new IllegalArgumentException("mycinema.panel.invalid");
        String normalized=PosterPolicy.normalize(settings.poster());
        if(normalized==null||!(local.cinemamanager.panels.assets.AssetReference.isAsset(normalized)?local.cinemamanager.panels.assets.PanelAssets.mayUseAsset(actor,this,normalized):PosterPolicy.maySave(actor,normalized)))throw new IllegalArgumentException("mycinema.panel.poster_denied");
        settings=settings.withPoster(normalized);
        if(newScope!=root.scope&&newScope!=PanelScope.MANUAL&&root.manager!=null){ManagerTile m=root.controller();if(m==null)throw new IllegalArgumentException("mycinema.panel.offline");if(!m.permits(actor,Double.POSITIVE_INFINITY))throw new IllegalArgumentException("mycinema.panel.permission");}
        long revision=group.members().stream().mapToLong(t->t.panelRevision).max().orElse(0)+1;
        // Validate and create every grant before mutating any member. Grant data never enters a client payload.
        Map<InfoTile,PosterGrant> grants=new IdentityHashMap<>();
        for(InfoTile member:group.members())grants.put(member,PosterGrant.issue(actor,member,normalized));
        for(InfoTile member:group.members()){
            member.panel=settings;member.scope=newScope;member.terminalRole=role;member.invalidTerminalRole=null;member.publicInvalidTerminalRole=false;member.panelRevision=revision;member.posterGrant=grants.get(member);
            // Lobby has no active hall binding. Switching back requires an explicit hall card binding.
            if(newScope==PanelScope.LOBBY)member.hallId="";
            if(newScope==PanelScope.MANUAL){member.manager=null;member.managerIdentity=null;member.hallId="";}
            member.dirty();
        }
        group.root().refreshNow();
    }
    public void bind(ServerPlayer actor,ManagerTile m,String hall){
        PanelGroup group=authorize(actor,null);InfoTile root=group.root();
        if(root.isAdvertising()&&root.screenMode!=local.cinemamanager.advertising.ScreenMode.INFORMATION)throw new IllegalArgumentException("mycinema.ad.information_first");
        if(root.scope==PanelScope.MANUAL)throw new IllegalArgumentException("mycinema.panel.manual_binding");
        if(root.isOperations()&&(root.owner==null||!root.owner.equals(actor.getUUID())||!root.owner.equals(m.owner)))throw new IllegalArgumentException("mycinema.panel.permission");
        if(!m.permits(actor,Double.POSITIVE_INFINITY)||m.getLevel()!=level)throw new IllegalArgumentException("mycinema.panel.permission");
        String targetHall="";
        if(root.scope==PanelScope.HALL){if(hall==null||hall.isBlank())throw new IllegalArgumentException("mycinema.panel.select_hall");m.state.hall(hall);targetHall=hall;}
        long revision=group.members().stream().mapToLong(t->t.panelRevision).max().orElse(0)+1;
        for(InfoTile member:group.members()){member.manager=m.getBlockPos();member.managerIdentity=m.identity;member.hallId=targetHall;member.panelRevision=revision;member.dirty();}
        group.root().refreshNow();
    }
    /** Explicit content-mode switch; physical geometry and saved picture remain independent. */
    public void screenMode(ServerPlayer actor,String signature,local.cinemamanager.advertising.ScreenMode next){
        authorize(actor,signature);
        if(!isAdvertising()||next==null)throw new IllegalArgumentException("mycinema.panel.invalid");
        if(next==local.cinemamanager.advertising.ScreenMode.MEDIA&&!((local.cinemamanager.advertising.AdvertisingBlock)getBlockState().getBlock()).emissive())throw new IllegalArgumentException("mycinema.ad.media_led_only");
        if(next==screenMode)return;
        local.cinemamanager.advertising.AdvertisingLifecycle.replaceForPoster(this,actor,signature,"",()->{screenMode=next;mirror=null;mirrorBoardOwner=null;publishedMirror=null;
        if(next!=local.cinemamanager.advertising.ScreenMode.INFORMATION){scope=PanelScope.MANUAL;manager=null;managerIdentity=null;hallId="";}
        panelRevision++;refreshNow();dirty();});
    }
    /** Called only with a server-owned source selection, after separate destination ownership checks. */
    public void bindMirror(ServerPlayer actor,String signature,local.cinemamanager.mirror.CinemaMirrors.BindingRef reference){
        authorize(actor,signature);
        if(!isAdvertising()||screenMode!=local.cinemamanager.advertising.ScreenMode.MEDIA
                ||!((local.cinemamanager.advertising.AdvertisingBlock)getBlockState().getBlock()).emissive())throw new IllegalArgumentException("mycinema.ad.media_first");
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)||reference==null||!actor.getUUID().equals(reference.boundBy())
                ||local.cinemamanager.mirror.CinemaMirrors.resolveLoaded(server,reference)==null)throw new IllegalArgumentException("mycinema.mirror.denied");
        mirror=reference;mirrorBoardOwner=owner;panelRevision++;publishedMirror=approvedMirror();dirty();
    }
    /** Public state contains identities only, never media URLs or command authority. */
    public local.cinemamanager.mirror.CinemaMirrors.BindingRef approvedMirror(){
        if(level!=null&&level.isClientSide)return publishedMirror;
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)||!isAdvertising()||screenMode!=local.cinemamanager.advertising.ScreenMode.MEDIA
                ||isRemoved()||!level.hasChunkAt(worldPosition)||level.getBlockEntity(worldPosition)!=this
                ||owner==null||!owner.equals(mirrorBoardOwner)||mirror==null
                ||local.cinemamanager.mirror.CinemaMirrors.resolveLoaded(server,mirror)==null)return null;
        return mirror;
    }
    public PublicPanelSnapshot projectNow(){return projectAt(System.currentTimeMillis());}
    private PublicPanelSnapshot projectAt(long now){
        if(isOperations()||invalidTerminalRole()||scope==PanelScope.MANUAL)return PublicPanelSnapshot.EMPTY;
        ManagerTile t=controller();
        return t==null?PublicPanelSnapshot.EMPTY:scope==PanelScope.HALL?hallProjection(t,now).publicData():PublicSchedule.project(t.state,scope,hallId,now,t::publicDuration);
    }
    private record HallProjection(PublicPanelSnapshot publicData,local.cinemamanager.catalog.FilmCatalog.Snapshot film,String selection) {}
    /** The same exact screening display supplies text and poster, including retained natural completion. */
    private HallProjection hallProjection(ManagerTile manager,long now){
        var current=manager.playback.publicDisplayProgramme(hallId);
        if(current!=null){
            var hall=manager.state.hall(hallId);var film=current.snapshot().film();var show=current.scheduled();
            String zone=manager.state.timezone==null?"":manager.state.timezone;
            try{if(!zone.isBlank())java.time.ZoneId.of(zone);}catch(RuntimeException invalid){zone="";}
            if(show==null||!zone.isBlank()){
                var row=new PublicShowRow(current.publicId(),film.title(),hall.name,show==null?0:show.start,
                        film.requireDurationMs(),show==null?0:show.end,show!=null);
                return new HallProjection(new PublicPanelSnapshot(zone,now,List.of(row)),current.snapshot(),"current|"+current.publicId()+"|"+current.snapshot().id());
            }
        }
        // Do not display an unrelated future film while a current lease is being restored or rejected.
        if(manager.playback.currentDisplayPending(hallId))return new HallProjection(PublicPanelSnapshot.EMPTY,null,"current_unavailable");
        // Film-backed running rows require the actual lease above; stale schedule status is not authority.
        java.util.function.Predicate<ManagerState.Show> eligible=show->!ManagerState.filmProgramme(show)
                ||show.status!=ManagerState.Status.PREPARING&&show.status!=ManagerState.Status.RUNNING;
        var data=PublicSchedule.project(manager.state,PanelScope.HALL,hallId,now,manager::publicDuration,eligible);
        var show=PublicSchedule.firstHallShow(manager.state,hallId,now,eligible);
        if(show==null)return new HallProjection(data,null,"");
        try{return new HallProjection(data,manager.library.snapshot(show),"show|"+show.id+"|"+show.filmSnapshot);}
        catch(RuntimeException invalid){return new HallProjection(data,null,"show|"+show.id);}
    }
    public List<String> diagnostics(){
        if(scope==PanelScope.MANUAL)return List.of(MessageText.key("panel.manual_ready"));
        ManagerTile t=controller();if(t==null)return List.of(MessageText.key("panel.offline"));
        List<String> fields=new ArrayList<>();fields.add((t.state.timezone==null||t.state.timezone.isBlank())?MessageText.key("panel.zone_unset"):t.state.timezone);
        if(scope==PanelScope.HALL){var hall=t.state.halls.stream().filter(h->h.id.equals(hallId)).findFirst();
            if(hall.isEmpty())fields.add(MessageText.key("panel.unbound"));
            else{var h=hall.get();fields.add(h.name);fields.add(h.deviceState);if(!h.issue.isBlank())fields.add(h.issue);}}
        if(scope==PanelScope.HALL){
            if(panel.layout()==PanelSettings.Layout.TEXT)fields.add(MessageText.key("panel.poster_text_layout"));
            else if(panel.poster().isBlank()){
                var selected=hallProjection(t,System.currentTimeMillis());
                String issue=selected.selection().isBlank()?"mycinema.panel.poster_no_programme":selected.selection().equals("current_unavailable")
                        ?"mycinema.panel.poster_current_unavailable":t.library.posterProblem(selected.film());
                fields.add(issue.isBlank()?MessageText.key("panel.poster_inherited"):issue);
            }
        }
        fields.add(t.state.notice);
        return fields.stream().limit(6).map(s->s.substring(0,Math.min(1024,s.length()))).toList();
    }
    public void tick(){
        if(level==null||level.isClientSide)return;
        if(isAdvertising()){var current=approvedMirror();if(!Objects.equals(current,publishedMirror)){publishedMirror=current;dirty();}}
        if(level.getGameTime()%40==0&&PanelGroup.of(this).root()==this)refreshNow();
    }
    private record PosterChoice(String source,String selection,ManagerTile manager,local.cinemamanager.catalog.FilmCatalog.Snapshot film) {
        private static final PosterChoice EMPTY=new PosterChoice("","",null,null);
    }
    /** Resolve the public hall's first current/next programme from its immutable saved film snapshot. */
    private PosterChoice choosePoster(PanelGroup group,long now){
        InfoTile root=group.root();
        if(root.isOperations()||root.invalidTerminalRole()||root.isAdvertising()&&root.screenMode==local.cinemamanager.advertising.ScreenMode.MEDIA)return PosterChoice.EMPTY;
        if(!(root.level instanceof net.minecraft.server.level.ServerLevel server)||!server.getServer().isSameThread())return PosterChoice.EMPTY;
        String normalized=PosterPolicy.normalize(root.panel.poster());
        if(normalized==null)return PosterChoice.EMPTY;
        if(normalized.isBlank()){
            if(root.scope!=PanelScope.HALL||root.isAdvertising()&&root.screenMode!=local.cinemamanager.advertising.ScreenMode.INFORMATION||root.isRemoved()
                    ||!root.level.hasChunkAt(root.worldPosition)||root.level.getBlockEntity(root.worldPosition)!=root)return PosterChoice.EMPTY;
            ManagerTile manager=root.controller();if(manager==null)return PosterChoice.EMPTY;
            var selected=root.hallProjection(manager,now);
            if(selected.film()==null)return PosterChoice.EMPTY;
            String approved=manager.library.approvedPosterSnapshot(selected.film());
            if(approved.isBlank())return PosterChoice.EMPTY;
            // The identifier stays server-private; the client sees one fresh opaque token only.
            return new PosterChoice(approved,"film|"+group.signature()+"|"+root.owner+"|"+manager.identity+"|"+selected.selection()+"|"+manager.library.posterSnapshotIdentity(selected.film()),manager,selected.film());
        }
        if(!PosterPolicy.mayPublish(normalized))
            for(InfoTile member:group.members())if(member.posterGrant==null||!member.posterGrant.current(member))return PosterChoice.EMPTY;
        return new PosterChoice(normalized,"panel|"+group.signature()+"|"+root.owner,null,null);
    }
    private String publicationToken(PanelGroup group,PosterChoice choice){return group.root().posterPublication.select(choice.selection(),choice.source());}
    /** One current server decision for block updates, guest responses and restored worlds. */
    public String approvedPoster(){
        if(level==null)return "";
        if(level.isClientSide)return publishedPoster;
        PanelGroup group=PanelGroup.of(this);PosterChoice choice=choosePoster(group,System.currentTimeMillis());
        publicationToken(group,choice);return choice.source();
    }
    /** A changed show/snapshot or a revoked source invalidates same-URI in-flight work as well. */
    public String approvedPosterToken(){
        if(level==null)return "";
        if(level.isClientSide)return publishedPosterToken;
        PanelGroup group=PanelGroup.of(this);return publicationToken(group,choosePoster(group,System.currentTimeMillis()));
    }
    /** Project an approved automatic source without modifying the owner's blank override or layout. */
    public PanelSettings publicSettings(){
        PanelGroup group=PanelGroup.of(this);InfoTile root=group.root();
        if(root.isOperations()||root.invalidTerminalRole())return root.operationsPublicSettings();
        String approved=root.approvedPoster();return root.panel.withPoster(approved).publicOnly(root.scope,approved);
    }
    /** Operations walls publish only role-specific rows and visual colours, never saved audience overrides. */
    private PanelSettings operationsPublicSettings(){return new PanelSettings(PanelSettings.Layout.TEXT,"","","","","","",panel.foreground(),panel.background(),panel.accent(),panel.textPercent(),PanelSettings.PUBLIC);}
    /** One clock sample keeps the guest poster and first programme row consistent at show boundaries. */
    public PanelNetwork.GuestData publicGuestData(){
        PanelGroup group=PanelGroup.of(this);InfoTile root=group.root();long now=System.currentTimeMillis();
        PosterChoice choice=choosePoster(group,now);publicationToken(group,choice);
        PanelSettings settings=root.isOperations()||root.invalidTerminalRole()?root.operationsPublicSettings():root.panel.withPoster(choice.source()).publicOnly(root.scope,choice.source());
        return new PanelNetwork.GuestData(settings,root.scope,root.projectAt(now),group.height(),group.width());
    }
    /** Server-only per-hop authorization. No URL is fetched here; no client supplies a grant. */
    public boolean permitsPosterTarget(String source,String target){
        if(!(level instanceof net.minecraft.server.level.ServerLevel server)||!server.getServer().isSameThread())return false;
        String normalizedSource=PosterPolicy.normalize(source), normalizedTarget=PosterPolicy.normalize(target);
        if(normalizedSource==null||normalizedTarget==null||normalizedSource.isBlank()||normalizedTarget.isBlank())return false;
        try{String scheme=java.net.URI.create(normalizedTarget).getScheme();if(!"http".equals(scheme)&&!"https".equals(scheme))return false;}catch(RuntimeException e){return false;}
        PanelGroup group=PanelGroup.of(this);PosterChoice choice=choosePoster(group,System.currentTimeMillis());publicationToken(group,choice);
        if(!normalizedSource.equals(choice.source()))return false;
        // Film authorship and source policy are rechecked even when the target itself is whitelisted.
        if(choice.film()!=null)return choice.manager().library.posterSnapshotTarget(choice.film(),normalizedSource,normalizedTarget);
        if(PosterPolicy.mayPublish(normalizedTarget))return true;
        for(InfoTile member:group.members())if(member.posterGrant==null||!member.posterGrant.currentTarget(member,normalizedTarget))return false;
        return true;
    }
    public void refreshNow(){
        if(level==null||level.isClientSide)return;
        PanelGroup group=PanelGroup.of(this);if(group.root()!=this){group.root().refreshNow();return;}
        long now=System.currentTimeMillis();PublicPanelSnapshot next=projectAt(now);PublicOperationsSnapshot operations=PublicOperationsSnapshot.project(this,now);List<String> diagnostic=diagnostics();String visualGroup=group.signature();
        PosterChoice choice=choosePoster(group,now);String poster=choice.source(),posterToken=publicationToken(group,choice);
        for(InfoTile member:group.members()){
            boolean isRoot=member==this;
            boolean changed=!member.operationsSnapshot.sameContent(operations)||!member.publicSnapshot.sameContent(next)||!member.publishedPoster.equals(poster)||!member.publishedPosterToken.equals(posterToken)||member.publishedAsRoot!=isRoot||!member.publishedVisualGroup.equals(visualGroup);
            member.publishedAsRoot=isRoot;member.publishedVisualGroup=visualGroup;
            member.publicSnapshot=next;member.operationsSnapshot=operations;member.panelFields=diagnostic;member.publishedPoster=poster;member.publishedPosterToken=posterToken;
            if(changed)member.dirty();
        }
    }
    /** Explicit public allowlist: never serialize a disk save and then try to redact it. */
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider r){
        CompoundTag t=new CompoundTag();t.putUUID("identity",identity);
        t.putString("terminal_role",terminalRole.encode());t.putBoolean("terminal_role_invalid",invalidTerminalRole());
        if(isOperations()&&PanelGroup.of(this).root()==this)t.put("operations_snapshot",PublicOperationsSnapshot.project(this,System.currentTimeMillis()).save());
        if(isAdvertising()){t.put("advertising_geometry",advertising.save());t.putString("screen_mode",screenMode.name());t.put("advertising_shape",advertisingShape.save());}
        if(isAdvertising()){var current=approvedMirror();if(current!=null)t.put("published_mirror",local.cinemamanager.mirror.CinemaMirrors.write(current));}
        PanelGroup group=PanelGroup.of(this);long now=System.currentTimeMillis();PosterChoice choice=choosePoster(group,now);String approved=choice.source();
        t.put("panel_settings",(isOperations()||invalidTerminalRole()?operationsPublicSettings():panel.withPoster(approved).publicOnly(scope,approved)).save());t.putString("panel_scope",scope.name());
        t.putUUID("panel_group",UUID.fromString(group.signature()));t.putLong("panel_revision",panelRevision);
        t.putString("published_poster",approved);t.putString("poster_publication",publicationToken(group,choice));
        PublicPanelSnapshot current=group.root()==this?projectAt(now):PublicPanelSnapshot.EMPTY;
        t.put("guest_snapshot",current.save());return t;
    }
    @Override protected void saveAdditional(CompoundTag t,HolderLookup.Provider r){
        super.saveAdditional(t,r);
        if(invalidTerminalRole!=null)t.put("terminal_role",invalidTerminalRole.copy());else t.putString("terminal_role",terminalRole.encode());
        if(isAdvertising()){t.put("advertising_geometry",advertising.save());t.putString("screen_mode",screenMode.name());t.put("advertising_shape",advertisingShape.save());}
        if(isAdvertising()){t.putInt("assembled_units",assembledUnits);if(mirror!=null&&mirrorBoardOwner!=null){t.put("mirror",local.cinemamanager.mirror.CinemaMirrors.write(mirror));t.putUUID("mirror_board_owner",mirrorBoardOwner);}}
        if(manager!=null&&managerIdentity!=null){t.putLong("manager",manager.asLong());t.putUUID("manager_id",managerIdentity);t.putString("hall",hallId);}
        t.put("panel_settings",panel.save());t.putString("panel_scope",scope.name());t.putUUID("panel_group",panelGroup);t.putLong("panel_revision",panelRevision);
        if(posterGrant!=null)t.put("poster_grant",posterGrant.save());
    }
    @Override protected void loadAdditional(CompoundTag t,HolderLookup.Provider r){
        super.loadAdditional(t,r);advertising=local.cinemamanager.advertising.AdvertisingGeometry.read(t.getCompound("advertising_geometry"));screenMode=local.cinemamanager.advertising.ScreenMode.saved(t.getString("screen_mode"));advertisingShape=local.cinemamanager.advertising.AdvertisingShape.read(t.getCompound("advertising_shape"));manager=t.contains("manager")?BlockPos.of(t.getLong("manager")):null;hallId=t.getString("hall");managerIdentity=t.hasUUID("manager_id")?t.getUUID("manager_id"):null;
        mirror=local.cinemamanager.mirror.CinemaMirrors.read(t.getCompound("mirror"));mirrorBoardOwner=t.hasUUID("mirror_board_owner")?t.getUUID("mirror_board_owner"):null;
        publishedMirror=local.cinemamanager.mirror.CinemaMirrors.read(t.getCompound("published_mirror"));assembledUnits=Math.clamp(t.getInt("assembled_units"),1,256);
        panel=t.contains("panel_settings")?PanelSettings.savedOrDefault(t.getCompound("panel_settings")):PanelSettings.DEFAULT;scope=PanelScope.savedOrDefault(t.getString("panel_scope"));
        terminalRole=TerminalRoleSettings.LEGACY;invalidTerminalRole=null;publicInvalidTerminalRole=t.getBoolean("terminal_role_invalid");
        if(t.contains("terminal_role")){try{if(!t.contains("terminal_role",Tag.TAG_STRING))throw new IllegalArgumentException();terminalRole=TerminalRoleSettings.decode(t.getString("terminal_role"));if(!isAdvertising()&&!terminalRole.validFor(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(getBlockState().getBlock()).toString(),scope)||isAdvertising()&&!terminalRole.equals(TerminalRoleSettings.LEGACY))throw new IllegalArgumentException();}catch(RuntimeException invalid){invalidTerminalRole=t.get("terminal_role").copy();terminalRole=TerminalRoleSettings.LEGACY;}}
        operationsSnapshot=PublicOperationsSnapshot.EMPTY;
        if(t.contains("operations_snapshot")){try{operationsSnapshot=PublicOperationsSnapshot.read(t.getCompound("operations_snapshot"));}catch(RuntimeException ignored){}}
        panelGroup=t.hasUUID("panel_group")?t.getUUID("panel_group"):identity;panelRevision=Math.max(0,t.getLong("panel_revision"));
        posterGrant=t.contains("poster_grant")?PosterGrant.read(t.getCompound("poster_grant")):null;
        panelFields=List.of();publicSnapshot=PublicPanelSnapshot.EMPTY;publishedPoster="";publishedPosterToken="";posterPublication.clear();
        // The tag exists only in a server-produced block update, not in our persisted save.
        if(t.contains("guest_snapshot")){
            try{publicSnapshot=PublicPanelSnapshot.read(t.getCompound("guest_snapshot"));}catch(RuntimeException ignored){}
            String poster=t.getString("published_poster"),token=t.getString("poster_publication");
            if(PosterPolicy.valid(poster)&&PosterPublicationState.validToken(token)){publishedPoster=poster;publishedPosterToken=token;}
        }
    }
}
