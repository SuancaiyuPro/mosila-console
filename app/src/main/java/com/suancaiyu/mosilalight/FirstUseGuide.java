package com.suancaiyu.mosilalight;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable first-use state; existing installs keep their selected console and parameters. */
final class FirstUseGuide {
    final SharedPreferences prefs;
    boolean required;
    int step;
    LampBrand choice;
    FirstUseGuide(Context context){
        prefs=context.getSharedPreferences("console",Context.MODE_PRIVATE);
        if(!prefs.contains("onboarding_complete")){
            boolean existing=prefs.contains("brand")||prefs.contains("mosilaTab")||prefs.contains("mdkTab");
            for(String name:new String[]{"light","device_profiles","modengke_p58_profiles","connection_memory","connection_memory_modengke_p58"})
                existing|=!context.getSharedPreferences(name,Context.MODE_PRIVATE).getAll().isEmpty();
            prefs.edit().putBoolean("onboarding_complete",existing).putInt("onboarding_step",1).apply();
        }
        required=!prefs.getBoolean("onboarding_complete",false);
        step=Math.max(1,Math.min(3,prefs.getInt("onboarding_step",1)));
        String id=prefs.getString("onboarding_brand","");for(LampBrand brand:LampBrand.values())if(brand.id.equals(id))choice=brand;
        if(choice==null)step=1;
    }
    void select(LampBrand brand){choice=brand;step=2;persist();}
    void move(int next){step=Math.max(1,Math.min(3,next));if(choice==null)step=1;persist();}
    private void persist(){SharedPreferences.Editor e=prefs.edit().putInt("onboarding_step",step);if(choice!=null)e.putString("onboarding_brand",choice.id);e.apply();}
    boolean finish(){
        if(!required||choice==null||step!=3)return false;
        prefs.edit().putBoolean("onboarding_complete",true).putString("brand",choice.id).remove("onboarding_step").remove("onboarding_brand").apply();required=false;return true;
    }
}
