package local.cinemamanager.seats;

public final class SeatProtocolChecks {
    private static void reject(String raw){try{SeatNetwork.parse(raw);throw new AssertionError("Accepted malformed values: "+raw);}catch(IllegalArgumentException expected){}}
    public static void main(String[] args){
        if(SeatNetwork.parse("{}").revision!=null)throw new AssertionError("missing revision was converted to zero");
        if(SeatNetwork.parse("{\"revision\":0}").revision!=0)throw new AssertionError("exact zero revision");
        reject("{\"revision\":1.5}");reject("{\"revision\":\"0\"}");reject("{\"revision\":9223372036854775808}");reject("{\"row\":1.5}");reject("{\"position\":2.75}");reject("{\"filterRow\":1000}");
        reject("{\"guide\":{\"hall\":\"\",\"zone\":\"\",\"firstRow\":1.5,\"lastRow\":2,\"seat\":0,\"arrow\":\"LEFT\"}}");
        String legacy="{\"guide\":{\"hall\":\"Hall\",\"zone\":\"Upper\",\"firstRow\":26,\"lastRow\":27,\"seat\":0,\"arrow\":\"LEFT\"}}";
        if(SeatNetwork.parse(legacy).guide.rowScheme()!=GuidePreset.RowScheme.NUMERIC)throw new AssertionError("legacy wire default");
        String display=legacy.replace("\"arrow\":\"LEFT\"","\"arrow\":\"LEFT\",\"rowScheme\":\"LETTERS\",\"rowPrefix\":\"ROW\"");
        GuidePreset guide=SeatNetwork.parse(display).guide;
        if(!guide.displayLabel().equals("Z-AA")||guide.rowPrefix()!=GuidePreset.RowPrefix.PLAIN||guide.firstRow()!=26||guide.lastRow()!=27)throw new AssertionError("letter wire prefixes normalize without changing seat indices");
        if(!SeatNetwork.parse(display.replace("\"ROW\"","\"LEGACY\"")).guide.equals(guide))throw new AssertionError("legacy letter wire prefix must also become plain");
        if(!SeatNetwork.parse(display.replace("\"LETTERS\"","\"NUMERIC\"")).guide.displayLabel().equals("ROW 26-27"))throw new AssertionError("numeric ROW prefix is retained");
        reject(display.replace("LETTERS","ROMAN"));reject(display.replace("\"ROW\"","\"UNKNOWN\""));
        reject(display.replace("\"firstRow\":26","\"firstRow\":\"26\""));
        String ranges=display.replace("\"seat\":0","\"seat\":0,\"firstSeat\":1,\"lastSeat\":12");
        if(!SeatNetwork.parse(ranges).guide.displayLabel().equals("Z-AA · S1-12"))throw new AssertionError("both entrance ranges must survive the wire");
        reject(ranges.replace("\"firstSeat\":1","\"firstSeat\":1.5"));reject(ranges.replace("\"firstSeat\":1","\"firstSeat\":0"));
        reject(ranges.replace("\"lastSeat\":12","\"lastSeat\":1000"));
        System.out.println("SeatProtocolChecks passed");
    }
}
