package local.cinemamanager.panels.assets;

import java.util.Map;
import java.util.WeakHashMap;

/** Connection-only admission before packets enter the server queue. Never reads game-world state. */
final class AssetIngress {
    private final int perConnection,globalLimit,windowLimit;
    private final long windowNanos;
    private final Map<Object,Window> windows=new WeakHashMap<>();
    private int pending;
    private static final class Window {long started;int count,pending;Window(long now){started=now;}}

    AssetIngress(int perConnection,int globalLimit,int windowLimit,long windowNanos){
        if(perConnection<1||globalLimit<perConnection||windowLimit<1||windowNanos<1)throw new IllegalArgumentException();
        this.perConnection=perConnection;this.globalLimit=globalLimit;this.windowLimit=windowLimit;this.windowNanos=windowNanos;
    }
    synchronized Permit acquire(Object connection,long now){
        Window w=windows.computeIfAbsent(connection,key->new Window(now));
        if(now-w.started<0||now-w.started>=windowNanos){w.started=now;w.count=0;}
        if(w.pending>=perConnection||pending>=globalLimit||w.count>=windowLimit)return null;
        w.count++;w.pending++;pending++;return new Permit(w);
    }
    final class Permit implements AutoCloseable {
        private final Window window;private boolean released;
        private Permit(Window window){this.window=window;}
        public void close(){synchronized(AssetIngress.this){if(!released){released=true;window.pending--;pending--;}}}
    }
}
