package local.cinemamanager.panels;

/** An allowlisted guest row. No media source, playback state, outcome or control token. */
public record PublicShowRow(String id, String title, String hall, long start, long durationMs, long end, boolean scheduled) {
    public PublicShowRow(String id,String title,String hall,long start,long durationMs,long end){this(id,title,hall,start,durationMs,end,true);}
    public PublicShowRow(String id,String title,String hall,long start,long durationMs){this(id,title,hall,start,durationMs,start);}
    public PublicShowRow {
        if (id == null || id.isBlank() || id.length() > 80 || title == null || title.isBlank()
                || title.length() > 80 || hall == null || hall.length() > 60 || durationMs < 0 || end < start
                ||!scheduled&&(start!=0||end!=0))
            throw new IllegalArgumentException("mycinema.panel.invalid");
    }
}
