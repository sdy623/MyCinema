package local.cinemamanager.seats;

import java.util.Map;

/** Original 3x5 alphanumeric glyphs. Row-only presets fit fully; combined row/seat ranges explicitly reject overflow. */
public final class BitTagRaster {
    private static final Map<Character,String> GLYPHS=Map.ofEntries(
        Map.entry('0',"111101101101111"),Map.entry('1',"010110010010111"),Map.entry('2',"111001111100111"),
        Map.entry('3',"111001111001111"),Map.entry('4',"101101111001001"),Map.entry('5',"111100111001111"),
        Map.entry('6',"111100111101111"),Map.entry('7',"111001010010010"),Map.entry('8',"111101111101111"),
        Map.entry('9',"111101111001111"),Map.entry('A',"010101111101101"),Map.entry('B',"110101110101110"),
        Map.entry('C',"111100100100111"),Map.entry('D',"110101101101110"),Map.entry('E',"111100110100111"),
        Map.entry('F',"111100110100100"),Map.entry('G',"111100101101111"),Map.entry('H',"101101111101101"),
        Map.entry('I',"111010010010111"),Map.entry('J',"001001001101111"),Map.entry('K',"101101110101101"),
        Map.entry('L',"100100100100111"),Map.entry('M',"101111111101101"),Map.entry('N',"101111111111101"),
        Map.entry('O',"111101101101111"),Map.entry('P',"110101110100100"),Map.entry('Q',"111101101111001"),
        Map.entry('R',"110101110101101"),Map.entry('S',"111100111001111"),Map.entry('T',"111010010010010"),
        Map.entry('U',"101101101101111"),Map.entry('V',"101101101101010"),Map.entry('W',"101101111111101"),
        Map.entry('X',"101101010101101"),Map.entry('Y',"101101010010010"),Map.entry('Z',"111001010100111"),
        Map.entry('-',"000000111000000"));
    public static boolean[][] raster(GuidePreset guide){
        boolean[][] pixels=new boolean[16][16];
        boolean header=guide.rowPrefix()==GuidePreset.RowPrefix.ROW;
        if(guide.hasSeatRange()){
            String rows=(guide.rowPrefix()==GuidePreset.RowPrefix.LEGACY?"R":"")+guide.firstLabel()+(guide.firstRow()==guide.lastRow()?"":"-"+guide.lastLabel());
            String seats=guide.seatRangeLabel();
            if(compactWidth(rows)>16||compactWidth(seats)>16)throw SeatRegistry.error("bit_ranges_long");
            compactLine(pixels,rows,header?5:0);compactLine(pixels,seats,header?11:6);
        }else{
            line(pixels,(guide.rowPrefix()==GuidePreset.RowPrefix.LEGACY?"R":"")+guide.firstLabel(),header?5:0);
            if(guide.seat()>0)line(pixels,"S"+guide.seat(),header?11:6);else if(guide.lastRow()!=guide.firstRow())line(pixels,"-"+guide.lastLabel(),header?11:6);
        }
        if(header){
            // Literal ROW occupies its own compact header; row/seat glyphs retain their full 3x5 size.
            header(pixels,"110101110101",3,0);header(pixels,"111101101111",3,4);header(pixels,"10001100011010101010",5,7);
            small(pixels,arrow(guide.arrow()),13,0);
        }else if(guide.arrow()==GuidePreset.Arrow.LEFT||guide.arrow()==GuidePreset.Arrow.RIGHT){
            // Preserve the original five-column arrows for old and plain presets.
            for(int x=0;x<5;x++)pixels[14][5+x]=true;
            int head=guide.arrow()==GuidePreset.Arrow.LEFT?5:9,shoulder=guide.arrow()==GuidePreset.Arrow.LEFT?6:8;
            pixels[13][shoulder]=true;pixels[15][shoulder]=true;pixels[14][head]=true;
        }else small(pixels,arrow(guide.arrow()),6,13);
        return pixels;
    }
    private static String arrow(GuidePreset.Arrow arrow){return switch(arrow){
        case NONE->"000000000";case LEFT->"010110010";case RIGHT->"010011010";
        case UP->"111010010";case DOWN->"010010111";case UP_LEFT->"111110101";case UP_RIGHT->"111011101";
        case DOWN_LEFT->"101110111";case DOWN_RIGHT->"101011111";
    };}
    private static void header(boolean[][] pixels,String glyph,int width,int left){for(int y=0;y<4;y++)for(int x=0;x<width;x++)pixels[y][left+x]=glyph.charAt(y*width+x)=='1';}
    private static void small(boolean[][] pixels,String glyph,int left,int top){for(int y=0;y<3;y++)for(int x=0;x<3;x++)pixels[top+y][left+x]=glyph.charAt(y*3+x)=='1';}
    /** Proportional dual-range text keeps every glyph; old row-only and single-seat rasters are untouched. */
    private static int compactWidth(String text){int width=Math.max(0,text.length()-1);for(int i=0;i<text.length();i++)width+=text.charAt(i)=='1'||text.charAt(i)=='-'?1:3;return width;}
    private static void compactLine(boolean[][] pixels,String text,int top){
        int width=compactWidth(text);if(width>16)throw SeatRegistry.error("bit_ranges_long");int left=(16-width)/2;
        for(int i=0;i<text.length();i++){
            char symbol=text.charAt(i);int columns=symbol=='1'||symbol=='-'?1:3;
            String glyph=symbol=='1'?"11111":symbol=='-'?"00100":GLYPHS.get(symbol);
            if(glyph==null)throw SeatRegistry.error("bit_text_long");
            for(int y=0;y<5;y++)for(int x=0;x<columns;x++)pixels[top+y][left+x]=glyph.charAt(y*columns+x)=='1';
            left+=columns+1;
        }
    }
    private static void line(boolean[][] pixels,String text,int y){
        int width=text.length()*4-1;if(width>16)throw SeatRegistry.error("bit_text_long");int left=(16-width)/2;
        for(int c=0;c<text.length();c++){String glyph=GLYPHS.get(text.charAt(c));if(glyph==null)throw SeatRegistry.error("bit_text_long");for(int row=0;row<5;row++)for(int col=0;col<3;col++)pixels[y+row][left+c*4+col]=glyph.charAt(row*3+col)=='1';}
    }
    private BitTagRaster(){}
}
