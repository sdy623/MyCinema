package local.cinemamanager;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import java.util.regex.Pattern;
/** Preserves translatable message fragments through existing string-only saved snapshots. */
public final class MessageText {
    private static final Pattern KEY=Pattern.compile("\\{tr:(mycinema\\.[a-z0-9_.]+)\\}");
    private MessageText() {}
    public static String key(String key){return "{tr:mycinema."+key+"}";}
    public static Component component(String value){
        if(value==null)return Component.empty();
        if(value.startsWith("mycinema.")&&value.matches("mycinema\\.[a-z0-9_.]+"))return Component.translatable(value);
        MutableComponent out=Component.empty();var match=KEY.matcher(value);int end=0;
        while(match.find()){out.append(Component.literal(value.substring(end,match.start())));out.append(Component.translatable(match.group(1)));end=match.end();}
        return out.append(Component.literal(value.substring(end)));
    }
    public static String display(String value){return component(value).getString();}
}
