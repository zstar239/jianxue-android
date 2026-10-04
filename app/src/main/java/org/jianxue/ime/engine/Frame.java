package org.jianxue.ime.engine;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public final class Frame {
    public final long revision;
    public final String raw, preedit, committed, completion, error, warning, digits, locked;
    public final boolean delete, privateInput, neural, translating;
    public final int page, pages;
    public final List<Candidate> candidates = new ArrayList<>();
    public final List<Candidate> allCandidates = new ArrayList<>();
    public final List<String> spellings = new ArrayList<>();

    public Frame(JSONObject value) {
        revision = value.optLong("revision");
        raw = value.optString("raw");
        digits = value.optString("digits"); locked = value.optString("locked");
        preedit = value.optString("preedit");
        committed = value.optString("committed");
        completion = value.isNull("completion") ? "" : value.optString("completion");
        error = value.isNull("error") ? "" : value.optString("error");
        warning = value.isNull("warning") ? "" : value.optString("warning");
        delete = value.optBoolean("delete");
        privateInput = value.optBoolean("private");
        neural = value.optBoolean("neural");
        translating = value.optBoolean("translating");
        page = value.optInt("page");
        pages = value.optInt("pages");
        JSONArray values = value.optJSONArray("candidates");
        if (values != null) for (int i = 0; i < values.length(); i++) {
            JSONObject item = values.optJSONObject(i);
            if (item != null) candidates.add(new Candidate(item));
        }
        JSONArray all = value.optJSONArray("all_candidates");
        if (all != null) for (int i = 0; i < all.length(); i++) {
            JSONObject item = all.optJSONObject(i); if (item != null) allCandidates.add(new Candidate(item));
        }
        JSONArray options = value.optJSONArray("spellings");
        if (options != null) for (int i = 0; i < options.length(); i++) spellings.add(options.optString(i));
    }

    public static final class Candidate {
        public final int index;
        public final String text, gloss, reading, source, code;
        public final boolean fresh;
        Candidate(JSONObject value) {
            index = value.optInt("index");
            text = value.optString("text");
            gloss = value.isNull("gloss") ? "" : value.optString("gloss");
            reading = value.isNull("reading") ? "" : value.optString("reading");
            source = value.optString("source");
            code = value.isNull("code") ? "" : value.optString("code");
            fresh = value.optBoolean("fresh");
        }
    }
}
