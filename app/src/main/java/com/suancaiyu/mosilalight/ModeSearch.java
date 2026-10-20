package com.suancaiyu.mosilalight;

import java.text.Normalizer;
import java.util.*;

/** Search keeps original protocol indices, even when the visible list is filtered. */
final class ModeSearch {
    static String normalize(String value) {
        return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }
    static ArrayList<Integer> find(List<String> names,String query) {
        String q=normalize(query);
        ArrayList<Integer> result=new ArrayList<>();
        for(int i=0;i<names.size();i++) {
            if(q.isEmpty() || normalize(names.get(i)).contains(q)
                || String.format(Locale.ROOT,"%03d",i+1).contains(q)) result.add(i);
        }
        return result;
    }
}
