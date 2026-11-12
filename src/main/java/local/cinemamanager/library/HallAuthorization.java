package local.cinemamanager.library;

import local.cinemamanager.*;
import local.cinemamanager.model.ManagerState;
import local.cinemamanager.policy.CinemaPermissions;
import me.srrapero720.waterframes.common.block.entity.DisplayTile;
import local.cinemamanager.theater.CinemaTerminalTile;
import local.cinemamanager.theater.CinemaDeviceTile;
import local.cinemamanager.projection.CinemaProjectorTile;
import local.cinemamanager.automation.CinemaAutomation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Selected-hall ownership repair and diagnostics; WF remains the authority for every grant. */
public final class HallAuthorization {
    private static final String PREFIX="mycinema.catalog.";
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("MyCinema/Authorization");
    public record ClaimReview(String status,String fingerprint,String terminal,List<String> projectors) {
        public ClaimReview { projectors=List.copyOf(projectors); }
        public boolean available(){return status.equals(PREFIX+"claim_available");}
    }
    private record Claim(CinemaTerminalTile terminal,List<CinemaProjectorTile> projectors,ClaimReview review) {}
    private HallAuthorization(){}
    private static String key(String suffix){return PREFIX+suffix;}
    public static IllegalArgumentException denied(ManagerTile manager,ServerPlayer actor,CinemaTerminalTile terminal,String code){
        String result=code.startsWith(PREFIX)?code:key(code);
        // Deliberately no source URI, media author, private path, token or exception text.
        LOG.warn("denied code={} actor={} manager={} managerOwner={} managerPos={} tms={} tmsOwner={} tmsPos={}",
                result,actor==null?null:actor.getUUID(),manager.identity,manager.owner,manager.getBlockPos(),
                terminal==null?null:terminal.identity(),terminal==null?null:terminal.theaterOwner(),terminal==null?null:terminal.getBlockPos());
        return new IllegalArgumentException(result);
    }
    public static CinemaTerminalTile target(ManagerTile manager,ServerPlayer actor,String hall){
        try{return manager.programmeTerminal(hall);}catch(IllegalArgumentException failure){
            String raw=failure.getMessage();String code=MessageText.key("message.m014").equals(raw)?"grant_tms_unloaded":
                    MessageText.key("message.m015").equals(raw)?"grant_tms_changed":
                    MessageText.key("message.m016").equals(raw)?"grant_enrollment_changed":"grant_hall_missing";
            throw denied(manager,actor,null,code);
        }
    }
    public static void requireManagerOwner(ManagerTile manager,ServerPlayer actor,CinemaTerminalTile terminal){
        if(manager.owner==null)throw denied(manager,actor,terminal,"grant_manager_unclaimed");
        if(actor==null||!manager.owner.equals(actor.getUUID()))throw denied(manager,actor,terminal,"grant_manager_owner_required");
        if(!manager.controlAllowed(actor)){
            String code=actor.level()==manager.getLevel()&&!actor.isSpectator()
                    &&actor.distanceToSqr(manager.getBlockPos().getCenter())>64?"grant_controller_distance":"grant_controller_permission";
            throw denied(manager,actor,terminal,code);
        }
    }
    public static void requireTmsOwner(ManagerTile manager,ServerPlayer actor,CinemaTerminalTile terminal){
        if(terminal.theaterOwner()==null)throw denied(manager,actor,terminal,"grant_tms_unclaimed");
        if(!terminal.ownedBy(manager.owner))throw denied(manager,actor,terminal,"grant_tms_owner_required");
    }
    /** Calls the unchanged locked WF implementation before classifying its refusal. */
    public static void grant(ManagerTile manager,ServerPlayer actor,CinemaTerminalTile terminal){
        String raw=CinemaAutomation.grant(terminal,actor,manager.getBlockPos(),manager.identity);
        if(raw!=null)throw denied(manager,actor,terminal,grantFailure(manager,actor,terminal,raw));
    }
    private static String grantFailure(ManagerTile manager,ServerPlayer actor,CinemaTerminalTile terminal,String raw){
        return switch(raw){
            case "自动授权参数不完整" -> "grant_invalid";
            case "控制中心身份或范围无效" -> "grant_controller_identity";
            case "当前服务器配置/离线权限不允许影院自动控制" -> "grant_automation_policy";
            case "需要真实影厅所有者在馆长终端 8 格内授权" -> {
                if(actor==null||actor.level()!=terminal.getLevel()||actor.isSpectator())yield "grant_actor_permission";
                if(terminal.theaterOwner()==null)yield "grant_tms_unclaimed";
                if(!terminal.ownedBy(actor.getUUID()))yield "grant_tms_owner_required";
                if(!actor.level().mayInteract(actor,terminal.getBlockPos()))yield "grant_tms_protected";
                if(!CinemaPermissions.canInteractBlock(actor,terminal.getDisplayBlock()))yield "grant_block_permission";
                if(!CinemaPermissions.canBindRemote(actor))yield "grant_bind_permission";
                if(!CinemaPermissions.canInteractRemote(actor))yield "grant_remote_permission";
                if(actor.distanceToSqr(manager.getBlockPos().getCenter())>64)yield "grant_controller_distance";
                if(!actor.level().mayInteract(actor,manager.getBlockPos()))yield "grant_controller_permission";
                yield "grant_unknown";
            }
            default -> "grant_unknown";
        };
    }
    public static String programmeStatus(ManagerTile manager,String hall){
        try{
            var terminal=manager.programmeTerminal(hall);
            if(manager.owner==null)return key("grant_manager_unclaimed");
            if(terminal.theaterOwner()==null)return key("grant_tms_unclaimed");
            if(!terminal.ownedBy(manager.owner))return key("grant_tms_owner_required");
            var grant=terminal.automationGrant();
            return key(grant!=null&&grant.matches(terminal.identity(),manager.identity,manager.owner,manager.getBlockPos())
                    ?"programme_grant_saved":"programme_grant_required");
        }catch(RuntimeException unavailable){return key("grant_target_unavailable");}
    }
    public static ClaimReview review(ManagerTile manager,ServerPlayer actor,String hall){
        try{return inspectClaim(manager,actor,hall).review();}
        catch(IllegalArgumentException unavailable){String reason=unavailable.getMessage();
            return new ClaimReview(reason!=null&&reason.startsWith(PREFIX)?reason:key("grant_target_unavailable"),"","",List.of());}
    }
    private static IllegalArgumentException invalid(String suffix){return new IllegalArgumentException(key(suffix));}
    /** Read-only and silent: periodic UI snapshots must not generate denial-log traffic. */
    private static Claim inspectClaim(ManagerTile manager,ServerPlayer actor,String hall){
        if(!(manager.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()
                ||manager.isRemoved()||!level.hasChunkAt(manager.getBlockPos())||level.getBlockEntity(manager.getBlockPos())!=manager)
            throw invalid("grant_controller_identity");
        if(manager.owner==null)throw invalid("grant_manager_unclaimed");
        if(actor==null||!manager.owner.equals(actor.getUUID()))throw invalid("grant_manager_owner_required");
        if(actor.level()!=level||actor.isSpectator()||level.getServer().getPlayerList().getPlayer(actor.getUUID())!=actor)
            throw invalid("grant_actor_permission");
        if(!actor.hasPermissions(4))throw invalid("claim_operator_required");
        if(actor.distanceToSqr(manager.getBlockPos().getCenter())>64)throw invalid("grant_controller_distance");
        if(!manager.permits(actor,8))throw invalid("grant_controller_permission");
        CinemaTerminalTile terminal;
        try{terminal=manager.programmeTerminal(hall);}catch(IllegalArgumentException unavailable){throw invalid("grant_target_unavailable");}
        if(terminal.theaterOwner()!=null)throw invalid("claim_tms_owned");
        if(!terminal.allows(actor)||!CinemaPermissions.canBindRemote(actor)||!CinemaPermissions.canInteractRemote(actor))throw invalid("claim_device_permission");
        if(terminal.projectors().size()!=2)throw invalid("claim_members_required");
        List<CinemaProjectorTile> members=new ArrayList<>();Set<UUID> identities=new HashSet<>();
        for(var link:terminal.projectors().entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()){
            BlockPos pos=link.getKey();
            if(!level.hasChunkAt(pos))throw invalid("claim_member_unloaded");
            if(!(level.getBlockEntity(pos) instanceof CinemaProjectorTile member)||member.isRemoved()
                    ||!member.identity().equals(link.getValue())||!identities.add(member.identity())
                    ||!terminal.contains(member)||!member.linkedTo(terminal)||member.terminal()!=terminal)throw invalid("claim_member_changed");
            if(member.theaterOwner()!=null)throw invalid("claim_member_owned");
            if(!member.allows(actor))throw invalid("claim_device_permission");
            members.add(member);
        }
        String terminalText=terminal.identity()+" @ "+coordinates(terminal.getBlockPos());
        List<String> memberText=members.stream().map(t->t.identity()+" @ "+coordinates(t.getBlockPos())).toList();
        String scope=level.dimension().location()+"|"+manager.identity+"|"+manager.owner+"|"+manager.getBlockPos().asLong()+"|"+hall+"|"+terminalText+"|"+String.join("|",memberText);
        String fingerprint;
        try{fingerprint=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(scope.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return new Claim(terminal,List.copyOf(members),new ClaimReview(key("claim_available"),fingerprint,terminalText,memberText));
    }
    private static String coordinates(BlockPos pos){return pos.getX()+", "+pos.getY()+", "+pos.getZ();}
    public static void claim(ManagerTile manager,ServerPlayer actor,String hall,long expected,String expectedFingerprint,boolean confirmed){
        if(!confirmed)throw denied(manager,actor,null,"confirm_required");
        if(manager.state.revision!=expected)throw denied(manager,actor,null,"stale_manager");
        if(expected>Long.MAX_VALUE-2)throw denied(manager,actor,null,"revision_exhausted");
        Claim claim;
        try{claim=inspectClaim(manager,actor,hall);}catch(IllegalArgumentException failure){throw denied(manager,actor,null,failure.getMessage());}
        if(expectedFingerprint==null||!claim.review().fingerprint().equals(expectedFingerprint))throw denied(manager,actor,claim.terminal(),"claim_changed");
        var terminal=claim.terminal();var level=(ServerLevel)manager.getLevel();
        List<CinemaDeviceTile> devices=new ArrayList<>();devices.add(terminal);devices.addAll(claim.projectors());
        var before=devices.stream().map(t->t.saveWithoutMetadata(level.registryAccess())).toList();
        try{
            terminal.data.paused=true;if(terminal.clock()!=null)terminal.clock().stop();
            for(var device:devices)device.owner(actor.getUUID());
            // Retire former leases/evidence and require fresh current-owner metadata. The URL,
            // playlist, saved media author, render geometry and calibration are left intact.
            terminal.sourceReplaced();terminal.data.paused=true;terminal.setDirty();
        }catch(RuntimeException failure){
            for(int i=0;i<devices.size();i++){devices.get(i).loadWithComponents(before.get(i),level.registryAccess());devices.get(i).setDirty();}
            terminal.data.paused=true;if(terminal.clock()!=null)terminal.clock().pause(true);terminal.setDirty();
            throw denied(manager,actor,terminal,"claim_failed");
        }
        manager.playback.retireClaimedHall(hall);var selected=manager.state.hall(hall);manager.state.takeOver(selected);
        selected.transport=ManagerState.Transport.STOPPED;selected.issue="";selected.durationMs=0;selected.playheadMs=0;
        manager.notice(key("hall_claimed"));
        LOG.info("claimed actor={} manager={} managerPos={} tms={} tmsPos={} projectorIds={} projectorPositions={}",
                actor.getUUID(),manager.identity,manager.getBlockPos(),terminal.identity(),terminal.getBlockPos(),
                claim.projectors().stream().map(CinemaProjectorTile::identity).toList(),claim.projectors().stream().map(CinemaProjectorTile::getBlockPos).toList());
    }
}
