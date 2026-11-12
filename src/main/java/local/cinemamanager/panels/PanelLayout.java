package local.cinemamanager.panels;
/** Pixel-space areas used by the world renderer, independent of Minecraft and language. */
public final class PanelLayout {
    public record Rect(float x,float y,float width,float height){}
    public record Areas(Rect poster,Rect text){}
    public record LineMeasure(PanelStyle.Entry entry,float width){}
    public record LinePosition(float x,float y,float scale){}
    private PanelLayout(){}
    /** Offsets are thousandths of the owning area, invariant under viewer resizing. */
    public static Rect place(Rect area,float width,float height,PanelStyle.Placement placement){
        if(area==null||placement==null||!Float.isFinite(width)||!Float.isFinite(height)||width<0||height<0||!Float.isFinite(area.x())||!Float.isFinite(area.y())||!Float.isFinite(area.width())||!Float.isFinite(area.height())||area.width()<0||area.height()<0)throw new IllegalArgumentException();
        float scale=Math.min(1,Math.min(area.width()/Math.max(1,width),area.height()/Math.max(1,height)));
        float w=width*scale,h=height*scale;
        float x=area.x()+switch(placement.horizontal()){case LEFT->0;case CENTER->(area.width()-w)/2;case RIGHT->area.width()-w;};
        float y=area.y()+switch(placement.vertical()){case TOP->0;case MIDDLE->(area.height()-h)/2;case BOTTOM->area.height()-h;};
        return new Rect(clamp(x+placement.manualX()*area.width()/1000f,area.x(),area.x()+area.width()-w),clamp(y+placement.manualY()*area.height()/1000f,area.y(),area.y()+area.height()-h),w,h);
    }
    private static float clamp(float value,float low,float high){return Math.max(low,Math.min(value,high));}
    /** Automatic entries retain normal flow. Explicit entries anchor independently inside the text area. */
    public static java.util.List<LinePosition> placeLines(Rect area,java.util.List<LineMeasure> lines,float advance,float glyphHeight,PanelStyle style){
        if(area==null||lines==null||style==null||!Float.isFinite(advance)||!Float.isFinite(glyphHeight)||glyphHeight<=0||advance<glyphHeight||area.width()<=0||area.height()<=0)throw new IllegalArgumentException();
        if(lines.isEmpty())return java.util.List.of();
        float widest=0;for(var line:lines){if(line==null||line.entry()==null||!Float.isFinite(line.width())||line.width()<0)throw new IllegalArgumentException();widest=Math.max(widest,line.width());}
        boolean independent=lines.stream().anyMatch(line->line.entry()!=PanelStyle.Entry.TEXT&&!style.placement(line.entry()).automatic());
        var result=new java.util.ArrayList<LinePosition>();
        for(int rowStart=0;rowStart<lines.size();){
            int rowEnd=lines.size();
            if(independent){
                rowEnd=rowStart;
                while(rowEnd<lines.size()&&!separator(lines.get(rowEnd)))rowEnd++;
                if(rowEnd==rowStart){
                    float rowHeight=area.height()/lines.size();
                    result.add(new LinePosition(area.x(),area.y()+rowStart*rowHeight,Math.min(1,rowHeight/glyphHeight)));
                    rowStart++;continue;
                }
            }
            Rect rowArea=independent?new Rect(area.x(),area.y()+area.height()*rowStart/lines.size(),area.width(),area.height()*(rowEnd-rowStart)/lines.size()):area;
            float rowWidth=0;for(int i=rowStart;i<rowEnd;i++)rowWidth=Math.max(rowWidth,lines.get(i).width());
            float rowHeight=(rowEnd-rowStart-1)*advance+glyphHeight;
            Rect group=place(rowArea,rowWidth,rowHeight,style.placement(PanelStyle.Entry.TEXT));
            float groupScale=Math.min(1,Math.min(rowArea.width()/Math.max(1,rowWidth),rowArea.height()/rowHeight));
            for(int start=rowStart;start<rowEnd;){
                int end=start+1;while(end<rowEnd&&lines.get(end).entry()==lines.get(start).entry())end++;
                PanelStyle.Placement entry=style.placement(lines.get(start).entry());
                boolean automatic=entry.automatic()||lines.get(start).entry()==PanelStyle.Entry.TEXT;
                float blockWidth=0;for(int i=start;i<end;i++)blockWidth=Math.max(blockWidth,lines.get(i).width());
                float blockHeight=(end-start-1)*advance+glyphHeight;
                Rect block=automatic?group:place(rowArea,blockWidth,blockHeight,entry);
                float scale=automatic?groupScale:Math.min(1,Math.min(rowArea.width()/Math.max(1,blockWidth),rowArea.height()/blockHeight));
                PanelStyle.Horizontal alignment=automatic?style.placement(PanelStyle.Entry.TEXT).horizontal():entry.horizontal();
                for(int i=start;i<end;i++){
                    float lineWidth=lines.get(i).width()*scale;
                    float x=block.x()+switch(alignment){case LEFT->0;case CENTER->(block.width()-lineWidth)/2;case RIGHT->block.width()-lineWidth;};
                    result.add(new LinePosition(x,automatic?group.y()+(i-rowStart)*advance*scale:block.y()+(i-start)*advance*scale,scale));
                }
                start=end;
            }
            rowStart=rowEnd;
        }
        return java.util.List.copyOf(result);
    }
    private static boolean separator(LineMeasure line){return line.entry()==PanelStyle.Entry.TEXT&&line.width()==0;}
    public record TextPage(Rect content,Rect indicator,int linesPerPage){public boolean hasIndicator(){return indicator.height()>0;}}
    /** Tiny text regions keep content readable and omit the optional marker instead of drawing outside bounds. */
    public static TextPage textPage(Rect area,int totalLines,float advance,float glyphHeight){
        if(area==null||area.width()<=0||area.height()<=0||totalLines<0||!Float.isFinite(advance)||!Float.isFinite(glyphHeight)||glyphHeight<=0||advance<glyphHeight)throw new IllegalArgumentException();
        int capacity=Math.max(1,(int)Math.floor(area.height()/advance));
        if(totalLines<=capacity||area.height()<advance+glyphHeight)return new TextPage(area,new Rect(area.x(),area.y()+area.height(),area.width(),0),capacity);
        Rect content=new Rect(area.x(),area.y(),area.width(),area.height()-advance);
        Rect indicator=new Rect(area.x(),area.y()+area.height()-glyphHeight,area.width(),glyphHeight);
        return new TextPage(content,indicator,Math.max(1,(int)Math.floor(content.height()/advance)));
    }
    public static Rect contain(Rect area,int imageWidth,int imageHeight,PanelStyle style){
        Rect fit=contain(area,imageWidth,imageHeight);
        return place(area,fit.width(),fit.height(),style.placement(PanelStyle.Entry.POSTER));
    }
    public static Areas areas(float width,float height,PanelSettings.Layout layout){
        return areas(width,height,layout,0);
    }
    public static Areas areas(float width,float height,PanelSettings.Layout layout,int posterPercent){
        if(posterPercent!=0&&(posterPercent<20||posterPercent>85))throw new IllegalArgumentException();
        if(!Float.isFinite(width)||!Float.isFinite(height)||width<=0||height<=0||layout==null)throw new IllegalArgumentException();
        float gap=Math.min(6,Math.min(width,height)/12);
        return switch(layout){
            case TEXT->new Areas(new Rect(0,0,0,0),new Rect(0,0,width,height));
            case POSTER_TOP->{float ph=posterPercent==0?Math.min(height*.52f,width*1.5f):height*posterPercent/100f;yield new Areas(new Rect(0,0,width,ph),new Rect(0,ph+gap,width,height-ph-gap));}
            case POSTER_LEFT->{float pw=posterPercent==0?width*.42f:width*posterPercent/100f;yield new Areas(new Rect(0,0,pw,height),new Rect(pw+gap,0,width-pw-gap,height));}
        };
    }
    public static Rect contain(Rect area,int imageWidth,int imageHeight){if(imageWidth<=0||imageHeight<=0)return new Rect(area.x,area.y,0,0);float scale=Math.min(area.width/imageWidth,area.height/imageHeight),w=imageWidth*scale,h=imageHeight*scale;return new Rect(area.x+(area.width-w)/2,area.y+(area.height-h)/2,w,h);}
}
