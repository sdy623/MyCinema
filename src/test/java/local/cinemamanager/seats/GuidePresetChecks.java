package local.cinemamanager.seats;

import java.util.*;

/** Pure model checks: display schemes cannot affect numeric seat identity or truncate microtag labels. */
public final class GuidePresetChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static void rejectJson(String json){try{SeatRegistry.JSON.fromJson(json,GuidePreset.class);}catch(RuntimeException expected){checks++;return;}throw new AssertionError("Accepted invalid guide JSON: "+json);}
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalArgumentException expected){checks++;return;}throw new AssertionError(message);}
    private static GuidePreset preset(int first,int last,int seat,GuidePreset.RowScheme scheme,GuidePreset.RowPrefix prefix,GuidePreset.Arrow arrow){return new GuidePreset("Hall","Upper",first,last,seat,arrow,scheme,prefix);}
    private static String legacyJson(){return "{\"hall\":\"Hall\",\"zone\":\"Upper\",\"firstRow\":26,\"lastRow\":27,\"seat\":0,\"arrow\":\"LEFT\"}";}
    private static String extra(String fields){String old=legacyJson();return old.substring(0,old.length()-1)+","+fields+"}";}
    private static void line(boolean[][] raster,String[] glyphs,int y){
        int width=glyphs.length*4-1,left=(16-width)/2;
        for(int row=0;row<5;row++)for(int x=0;x<16;x++){
            int column=x-left,glyph=column/4,offset=column%4;
            boolean expected=column>=0&&glyph<glyphs.length&&offset<3&&glyphs[glyph].charAt(row*3+offset)=='1';
            check(raster[y+row][x]==expected,"complete centered glyph at "+x+","+(y+row));
        }
    }
    private static void compactLine(boolean[][] raster,String[] glyphs,int[] widths,int top){
        int total=Arrays.stream(widths).sum()+widths.length-1,left=(16-total)/2;boolean[][] expected=new boolean[5][16];
        for(int g=0;g<glyphs.length;g++){for(int y=0;y<5;y++)for(int x=0;x<widths[g];x++)expected[y][left+x]=glyphs[g].charAt(y*widths[g]+x)=='1';left+=widths[g]+1;}
        for(int y=0;y<5;y++)for(int x=0;x<16;x++)check(raster[top+y][x]==expected[y][x],"complete proportional range glyph at "+x+","+(top+y));
    }
    private static String face(boolean[][] raster){StringBuilder result=new StringBuilder();for(boolean[] row:raster){for(boolean pixel:row)result.append(pixel?'1':'0');result.append('/');}return result.toString();}
    public static void run(){
        GuidePreset old=SeatRegistry.JSON.fromJson(legacyJson(),GuidePreset.class);
        check(old.rowScheme()==GuidePreset.RowScheme.NUMERIC&&old.rowPrefix()==GuidePreset.RowPrefix.LEGACY,"old JSON defaults preserve numeric legacy display");
        check(old.equals(new GuidePreset("Hall","Upper",26,27,0,GuidePreset.Arrow.LEFT)),"old constructor and old JSON stay equal");
        check(old.displayLabel().equals("R26-27"),"old item caption remains unchanged");
        check(SeatRegistry.JSON.fromJson(extra("\"rowScheme\":null,\"rowPrefix\":null"),GuidePreset.class).equals(old),"null optional fields migrate like missing fields");
        rejectJson(extra("\"rowScheme\":\"ROMAN\""));rejectJson(extra("\"rowPrefix\":\"SECRET\""));rejectJson(extra("\"rowScheme\":7"));rejectJson(extra("\"rowPrefix\":false"));
        rejectJson(legacyJson().replace("\"firstRow\":26","\"firstRow\":26.5"));rejectJson(legacyJson().replace("\"firstRow\":26","\"firstRow\":\"26\""));
        GuidePreset letters=preset(26,27,0,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,GuidePreset.Arrow.LEFT);
        int[] indices={1,2,26,27,52,53,702,703,999};String[] expected={"A","B","Z","AA","AZ","BA","ZZ","AAA","ALK"};
        for(int i=0;i<indices.length;i++)check(letters.rowLabel(indices[i]).equals(expected[i]),"letter transition "+indices[i]);
        check(letters.displayLabel().equals("Z-AA")&&letters.firstRow()==26&&letters.lastRow()==27,"range display leaves numeric indices intact");
        check(preset(1,3,0,GuidePreset.RowScheme.NUMERIC,GuidePreset.RowPrefix.ROW,GuidePreset.Arrow.NONE).displayLabel().equals("ROW 1-3"),"literal numeric ROW prefix");
        check(preset(27,27,999,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.ROW,GuidePreset.Arrow.NONE).displayLabel().equals("AA S999"),"letter display removes ROW but keeps numeric seat number");
        for(GuidePreset.RowPrefix requested:GuidePreset.RowPrefix.values()){
            GuidePreset normalized=preset(26,27,0,GuidePreset.RowScheme.LETTERS,requested,GuidePreset.Arrow.LEFT);
            check(normalized.rowPrefix()==GuidePreset.RowPrefix.PLAIN&&normalized.equals(letters),"every requested letter prefix normalizes to plain");
            GuidePreset decoded=SeatRegistry.JSON.fromJson(extra("\"rowScheme\":\"LETTERS\",\"rowPrefix\":\""+requested.name()+"\""),GuidePreset.class);
            check(decoded.equals(letters)&&face(BitTagRaster.raster(decoded)).equals(face(BitTagRaster.raster(letters))),"saved letter prefix normalizes before rendering");
        }
        check(SeatRegistry.JSON.fromJson(extra("\"rowScheme\":\"LETTERS\""),GuidePreset.class).equals(letters),"missing alphabetic prefix becomes plain");
        check(SeatRegistry.JSON.fromJson(extra("\"rowScheme\":\"LETTERS\",\"rowPrefix\":null"),GuidePreset.class).equals(letters),"null alphabetic prefix becomes plain");
        Set<String> alphabet=new HashSet<>();
        for(int i=1;i<=26;i++)check(alphabet.add(face(BitTagRaster.raster(preset(i,i,0,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,GuidePreset.Arrow.NONE)))),"each A-Z glyph is distinct");
        for(GuidePreset.RowScheme scheme:GuidePreset.RowScheme.values())for(GuidePreset.RowPrefix prefix:GuidePreset.RowPrefix.values()){
            for(int index=1;index<=999;index++){
                GuidePreset range=preset(index,999,0,scheme,prefix,GuidePreset.Arrow.NONE);
                boolean[][] raster=BitTagRaster.raster(range);check(raster.length==16&&Arrays.stream(raster).allMatch(row->row.length==16),"all permitted ranges fit "+scheme+" / "+prefix+" / "+index);
                check(range.equals(SeatRegistry.JSON.fromJson(SeatRegistry.JSON.toJson(range),GuidePreset.class)),"scheme and prefix round trip at "+index);
                if(scheme==GuidePreset.RowScheme.LETTERS)check(range.rowPrefix()==GuidePreset.RowPrefix.PLAIN&&range.displayLabel().equals(range.firstLabel()+(index==999?"":"-"+range.lastLabel())),"all alphabetic indices remain bare under every requested prefix");
                BitTagRaster.raster(preset(index,index,999,scheme,prefix,GuidePreset.Arrow.RIGHT));
            }
            for(GuidePreset.Arrow arrow:GuidePreset.Arrow.values()){
                GuidePreset range=preset(26,27,0,scheme,prefix,arrow);
                check(range.equals(SeatRegistry.JSON.fromJson(SeatRegistry.JSON.toJson(range),GuidePreset.class)),"all schemes, prefixes and manual arrows round trip");
            }
        }
        String A="010101111101101",Z="111001010100111",dash="000000111000000";
        line(BitTagRaster.raster(preset(1,1,0,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,GuidePreset.Arrow.NONE)),new String[]{A},0);
        line(BitTagRaster.raster(letters),new String[]{Z},0);line(BitTagRaster.raster(letters),new String[]{dash,A,A},6);
        GuidePreset row=preset(1,2,0,GuidePreset.RowScheme.NUMERIC,GuidePreset.RowPrefix.ROW,GuidePreset.Arrow.LEFT);
        boolean[][] header=BitTagRaster.raster(row);line(header,new String[]{"010110010010111"},5);line(header,new String[]{dash,"111001111100111"},11);
        String[] small={"110101110101","111101101111","10001100011010101010"};int[] widths={3,3,5},lefts={0,4,7};
        for(int glyph=0;glyph<3;glyph++)for(int y=0;y<4;y++)for(int x=0;x<widths[glyph];x++)check(header[y][lefts[glyph]+x]==(small[glyph].charAt(y*widths[glyph]+x)=='1'),"literal ROW header has every pixel");
        boolean[][] right=BitTagRaster.raster(preset(1,2,0,GuidePreset.RowScheme.NUMERIC,GuidePreset.RowPrefix.ROW,GuidePreset.Arrow.RIGHT));
        check(header[1][13]&&!header[1][15]&&!right[1][13]&&right[1][15],"compact header arrows remain directionally distinct");
        for(GuidePreset.RowScheme scheme:GuidePreset.RowScheme.values())for(GuidePreset.RowPrefix prefix:GuidePreset.RowPrefix.values()){
            Set<String> arrows=new HashSet<>();for(GuidePreset.Arrow arrow:GuidePreset.Arrow.values())check(arrows.add(face(BitTagRaster.raster(preset(1,1,0,scheme,prefix,arrow)))),"every arrow remains distinct in "+prefix);
        }
        check(!old.hasSeatRange()&&old.firstSeat()==0&&old.lastSeat()==0,"older presets have no implied seat range");
        GuidePreset aisle=new GuidePreset("Hall","Upper",1,8,0,GuidePreset.Arrow.LEFT,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.ROW,1,12);
        check(aisle.displayLabel().equals("A-H · S1-12")&&aisle.rowPrefix()==GuidePreset.RowPrefix.PLAIN,"explicit synthetic row and seat ranges with bare letter rows");
        check(aisle.equals(SeatRegistry.JSON.fromJson(SeatRegistry.JSON.toJson(aisle),GuidePreset.class)),"both range endpoints survive JSON round trip");
        boolean[][] aislePixels=BitTagRaster.raster(aisle);
        compactLine(aislePixels,new String[]{A,"00100","101101111101101"},new int[]{3,1,3},0);
        compactLine(aislePixels,new String[]{"111100111001111","11111","00100","11111","111001111100111"},new int[]{3,1,1,1,3},6);
        GuidePreset aisle24=new GuidePreset("Hall","Upper",1,8,0,GuidePreset.Arrow.LEFT,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,1,24);
        compactLine(BitTagRaster.raster(aisle24),new String[]{"111100111001111","11111","00100","111001111100111","101101111001001"},new int[]{3,1,1,3,3},6);
        GuidePreset large=new GuidePreset("Hall","Upper",703,999,0,GuidePreset.Arrow.LEFT,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.ROW,100,999);
        check(large.displayLabel().equals("AAA-ALK · S100-999"),"ordinary guide preserves even ranges too large for C&B");
        reject(()->BitTagRaster.raster(large),"oversized ranges must never be cropped");
        reject(()->BitTagRaster.raster(new GuidePreset("","",1,8,0,GuidePreset.Arrow.NONE,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,12,24)),"oversized seat range rejected even when row range fits");
        reject(()->new GuidePreset("","",1,8,0,GuidePreset.Arrow.NONE,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,0,12),"partial seat range rejected");
        reject(()->new GuidePreset("","",1,8,0,GuidePreset.Arrow.NONE,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,12,1),"reversed seat range rejected");
        reject(()->new GuidePreset("","",1,1,5,GuidePreset.Arrow.NONE,GuidePreset.RowScheme.LETTERS,GuidePreset.RowPrefix.PLAIN,1,12),"single-seat and seat-range choices cannot be mixed");
        rejectJson(extra("\"firstSeat\":1,\"lastSeat\":1000"));rejectJson(extra("\"firstSeat\":1.5,\"lastSeat\":12"));rejectJson(extra("\"firstSeat\":\"1\",\"lastSeat\":12"));
        check(SeatRegistry.JSON.fromJson(extra("\"firstSeat\":null,\"lastSeat\":null"),GuidePreset.class).equals(old),"null optional seat ranges migrate to none");
        // This fixed pre-feature raster protects compatibility independently from current glyph lookup/layout.
        check(face(BitTagRaster.raster(new GuidePreset("Hall","Upper",123,456,0,GuidePreset.Arrow.LEFT))).equals(
            "1100010011101110/1010110000100010/1100010011101110/1010010010000010/1010111011101110/0000000000000000/0000101011101110/0000101010001000/1110111011101110/0000001000101010/0000001011101110/0000000000000000/0000000000000000/0000001000000000/0000011111000000/0000001000000000/"),"legacy R123/-456 LEFT raster is byte-for-byte unchanged");
        System.out.println("GuidePresetChecks passed: "+checks+" checks; legacy JSON/raster, A-Z/AA/AAA/ALK, all 1..999 labels, numeric prefixes, plain-only letter normalization, complete dual ranges with overflow rejection, exact glyphs and arrows");
    }
    public static void main(String[] args){run();}
}
