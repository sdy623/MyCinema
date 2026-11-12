package local.cinemamanager;

import me.srrapero720.waterui.format.*;
import net.minecraft.resources.ResourceLocation;
import java.nio.file.*;
import java.util.*;

/** Actual deployed WaterUI parser/registry checks. No game, window, decoder or media is started. */
public final class WaterUiSchemaChecks {
    private static final Set<String> GENERIC_PROPERTIES=Set.of("id","width","height","weight","margin","padding","border","align","justify","anchor","outsideAnchor","shadow","enabled","visible","hidden","elevation","scale","tooltip","face","outline","scrollX","scrollY");
    public record Report(int documents,int assertions) {}
    private int assertions;
    private void check(boolean condition,String message){assertions++;if(!condition)throw new AssertionError(message);}

    /** Discover every template instead of silently omitting future filenames or UI families. */
    public static Report validateAll(Path directory)throws Exception{
        UIRegistry.bootstrap();
        var checks=new WaterUiSchemaChecks();
        checks.check(Files.isDirectory(directory),"UI template directory exists: "+directory);
        List<Path> files;
        try(var paths=Files.walk(directory)){
            files=paths.filter(Files::isRegularFile).filter(path->path.getFileName().toString().endsWith(".ui")).sorted().toList();
        }
        checks.check(!files.isEmpty(),"at least one UI template is discovered");
        for(Path file:files){
            String relative=directory.relativize(file).toString().replace('\\','/');
            String id=relative.substring(0,relative.length()-3);
            var document=UIParser.parse(UILexer.tokenize(Files.readString(file),file.toString()),ResourceLocation.fromNamespaceAndPath("mycinema",id));
            checks.check(document!=null,"WaterUI document parse: "+relative);
            checks.check(document.themeId().toString().equals("waterframes:waterui"),"original WaterFrames theme: "+relative);
            checks.schema(document,document.tree(),new HashSet<>());
        }
        return new Report(files.size(),checks.assertions);
    }

    private void schema(UIDocument document,List<UINode> nodes,Set<String> ids){
        for(UINode node:nodes){
            boolean imported=document.imports().stream().anyMatch(value->node.tag().equals(value.alias()));
            if(!imported){
                check(UIRegistry.factory(node.tag())!=null,"registered widget "+node.source()+':'+node.line()+' '+node.tag());
                for(String name:node.props().keySet()){
                    boolean valid=UIParser.isEvent(name)?UIRegistry.eventBinder(node.tag(),name)!=null
                            :GENERIC_PROPERTIES.contains(name)||UIRegistry.tagProperty(node.tag(),name)!=null;
                    check(valid,"registered property/event "+node.source()+':'+node.line()+' '+node.tag()+'.'+name);
                    if(node.props().get(name) instanceof UIValue.Id id&&document.decls().stream().anyMatch(decl->decl.name().equals(id.name())&&decl.mode()==UIDecl.Mode.LIVE))
                        check(UIRegistry.binding(node.tag(),name)!=null,"actual live binding "+node.source()+':'+node.line()+' '+node.tag()+'.'+name+" <- "+id.name());
                }
                if(node.props().get("id") instanceof UIValue.Str id)check(ids.add(id.text()),"unique element id "+node.source()+':'+node.line()+' '+id.text());
                if(node.tag().equals("Button"))check(UIRegistry.eventBinder(node.tag(),"onClick")!=null,"registered button click binder "+node.source()+':'+node.line());
            }
            schema(document,node.children(),ids);
        }
    }

    public static void main(String[] args)throws Exception{
        Path directory=Path.of(args.length==0?"src/main/resources/assets/mycinema/ui":args[0]);
        Report result=validateAll(directory);
        System.out.println("PASS: "+result.documents()+" discovered WaterUI templates; "+result.assertions()+" actual parser/property/event/live-binding schema checks.");
        System.out.println("This validates parser/registry contracts only, not UI inflation, layout, rendering, interactions or game behavior.");
    }
}
