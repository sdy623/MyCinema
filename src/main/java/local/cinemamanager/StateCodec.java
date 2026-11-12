package local.cinemamanager;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import local.cinemamanager.model.ManagerState;

public final class StateCodec {
    private static final Gson JSON = new GsonBuilder().disableHtmlEscaping().create();
    public static String encode(ManagerState state) { return JSON.toJson(state); }
    public static ManagerState decode(String json) {
        if (json.length() > 512_000) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m101"));
        ManagerState state = JSON.fromJson(json, ManagerState.class);
        if (state == null) throw new IllegalArgumentException(local.cinemamanager.MessageText.key("message.m102"));
        state.validate(); return state;
    }
    public static String publicView(ManagerState state) {
        ManagerState copy = decode(encode(state));
        for (ManagerState.Show s : copy.shows) s.url = "";
        return encode(copy);
    }
}
