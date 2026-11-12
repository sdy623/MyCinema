package local.cinemamanager.library;
import local.cinemamanager.catalog.CatalogException;
import net.minecraft.nbt.*;
/** Bounded chunks avoid NBT StringTag's 65,535-byte modified-UTF ceiling. */
public final class CatalogStorage {
 private static final int CHARS=10_000,MAX_CHUNKS=128;
 private CatalogStorage(){}
 public static ListTag write(String text){ListTag chunks=new ListTag();for(int start=0;start<text.length();){int end=Math.min(text.length(),start+CHARS);if(end<text.length()&&Character.isHighSurrogate(text.charAt(end-1)))end--;chunks.add(StringTag.valueOf(text.substring(start,end)));start=end;if(chunks.size()>MAX_CHUNKS)throw new CatalogException("byte_limit");}return chunks;}
 public static String read(CompoundTag tag,String key){if(!tag.contains(key))return "";if(!tag.contains(key,Tag.TAG_LIST))throw new CatalogException("corrupt");ListTag chunks=tag.getList(key,Tag.TAG_STRING);if(chunks.size()>MAX_CHUNKS||((ListTag)tag.get(key)).size()!=chunks.size())throw new CatalogException("corrupt");StringBuilder out=new StringBuilder();for(int i=0;i<chunks.size();i++){String value=chunks.getString(i);if(value.length()>CHARS)throw new CatalogException("corrupt");out.append(value);}return out.toString();}
}
