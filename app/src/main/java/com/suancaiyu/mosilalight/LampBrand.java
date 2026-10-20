package com.suancaiyu.mosilalight;

/** Brand-specific entry points. Unknown protocols expose no lamp controls. */
enum LampBrand {
    MOSILA("mosila","mosila",true,"device_profiles"),
    MODENGKE("modengke","魔灯客",true,"device_profiles_modengke");
    final String id,title,profileNamespace;
    final boolean supported;
    LampBrand(String id,String title,boolean supported,String profileNamespace){this.id=id;this.title=title;this.supported=supported;this.profileNamespace=profileNamespace;}
    static LampBrand fromId(String id){for(LampBrand brand:values())if(brand.id.equals(id))return brand;return MOSILA;}
}
