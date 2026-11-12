package local.cinemamanager.panels;

/** Standalone java.base-only regression suite for source and snapshot callback invalidation. */
public final class PosterPublicationStateChecks {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        PosterPublicationState state=new PosterPublicationState();
        check(state.select("","").isEmpty(),"blank source has no authority token");
        String first=state.select("show-a:snapshot-a","https://example.invalid/same-image");
        check(PosterPublicationState.validToken(first),"token is a UUID");
        check(first.equals(state.select("show-a:snapshot-a","https://example.invalid/same-image")),"refresh preserves an unchanged publication");
        String second=state.select("show-b:snapshot-b","https://example.invalid/same-image");
        check(!first.equals(second),"different film invalidates same-source callbacks");
        check(state.select("","").isEmpty(),"withdrawal clears token");
        String renewed=state.select("show-b:snapshot-b","https://example.invalid/same-image");
        check(!second.equals(renewed),"restoring permission does not restore old callback authority");
        check(!renewed.equals(state.select("show-b:snapshot-b","https://example.invalid/changed-image")),"URI change invalidates token");
        String beforeReload=state.select("show-b:snapshot-b","mycinema-asset:"+"a".repeat(64));state.clear();
        check(!beforeReload.equals(state.select("show-b:snapshot-b","mycinema-asset:"+"a".repeat(64))),"reload starts a fresh lifetime");
        check(!PosterPublicationState.validToken("")&&!PosterPublicationState.validToken(null)&&!PosterPublicationState.validToken("snapshot-private"),"invalid protocol token rejected");
        System.out.println("PASS: "+checks+" poster publication lifetime checks.");
    }
}
