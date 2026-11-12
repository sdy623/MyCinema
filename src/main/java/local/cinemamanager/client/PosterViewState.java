package local.cinemamanager.client;

import local.cinemamanager.panels.PanelSettings;

/** Private owner snapshot and public redacted tile have deliberately different source visibility. */
final class PosterViewState {
    enum State { EMPTY, AUTOMATIC_EMPTY, WITHHELD, CHANGED, AVAILABLE, AUTOMATIC_AVAILABLE }
    static State of(String savedOwnerSource,String publishedSource){
        return of(savedOwnerSource,publishedSource,false);
    }
    static State of(String savedOwnerSource,String publishedSource,boolean automatic){
        boolean saved=savedOwnerSource!=null&&!savedOwnerSource.isBlank();
        boolean published=publishedSource!=null&&!publishedSource.isBlank();
        if(!saved)return automatic?(published?State.AUTOMATIC_AVAILABLE:State.AUTOMATIC_EMPTY):(published?State.CHANGED:State.EMPTY);
        if(!published)return State.WITHHELD;
        return savedOwnerSource.equals(publishedSource)?State.AVAILABLE:State.CHANGED;
    }
    static boolean showsPoster(PanelSettings.Layout layout){
        return layout==PanelSettings.Layout.POSTER_TOP||layout==PanelSettings.Layout.POSTER_LEFT;
    }
    /** A visitor may render only the same approved source on the still-loaded, matching tile. */
    static boolean guestMayDisplay(PanelSettings.Layout layout,String expectedIdentity,String actualIdentity,
                                   String guestSource,String publishedSource){
        return showsPoster(layout)&&expectedIdentity!=null&&expectedIdentity.equals(actualIdentity)
                &&guestSource!=null&&!guestSource.isBlank()&&guestSource.equals(publishedSource);
    }
    private PosterViewState(){}
}
