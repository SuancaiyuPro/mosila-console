package com.suancaiyu.mosilalight;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35,manifest=Config.NONE)
public class AppInfoTest {
    ActivityController<MainActivity> c;MainActivity h;
    @Before public void setup(){RuntimeEnvironment.getApplication().setTheme(android.R.style.Theme_Material_NoActionBar);}
    @After public void close(){if(c!=null)c.pause().stop().destroy();}
    void open(boolean existing){if(existing)RuntimeEnvironment.getApplication().getSharedPreferences("console",0).edit().putBoolean("onboarding_complete",true).commit();c=Robolectric.buildActivity(MainActivity.class).create().start().resume().visible();h=c.get();ShadowLooper.idleMainLooper();}
    void restart(){c.pause().stop().destroy();open(true);}
    TextView find(View v,String s){if(v instanceof TextView&&s.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){TextView t=find(((ViewGroup)v).getChildAt(i),s);if(t!=null)return t;}return null;}
    void dismissNotice(){h.freeNotice.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();}
    @Test public void noticeUsesExactCopyAndDontRemindPersistsAcrossBrandsAndRestarts(){
        open(true);assertNotNull(h.freeNotice);View content=h.freeNotice.getWindow().getDecorView();assertNotNull(find(content,MainActivity.FREE_NOTICE_TEXT));CheckBox remember=(CheckBox)find(content,"不再提醒");assertNotNull(remember);assertFalse(remember.isChecked());remember.setChecked(true);assertTrue(h.consolePreferences.getBoolean("hide_free_notice",false));dismissNotice();h.selectBrand(LampBrand.MODENGKE);h.showFreeNoticeIfNeeded();assertNull(h.freeNotice);restart();assertNull(h.freeNotice);
    }
    @Test public void uncheckedNoticeReturnsOnNextColdStartButNotResumeOrBrandSwitch(){
        open(true);dismissNotice();assertFalse(h.consolePreferences.getBoolean("hide_free_notice",false));c.pause().resume();ShadowLooper.idleMainLooper();assertNull(h.freeNotice);h.selectBrand(LampBrand.MODENGKE);h.showFreeNoticeIfNeeded();assertNull(h.freeNotice);restart();assertNotNull(h.freeNotice);
    }
    @Test public void firstInstallShowsNoticeOnlyAfterAllGuideSteps(){
        open(false);assertTrue(h.needsOnboarding());assertNull(h.freeNotice);h.showFreeNoticeIfNeeded();assertNull(h.freeNotice);h.selectBrand(LampBrand.MODENGKE);h.firstUse.move(3);h.finishOnboarding();ShadowLooper.idleMainLooper();assertFalse(h.needsOnboarding());assertNotNull(h.freeNotice);assertEquals(LampBrand.MODENGKE,h.activeBrand);
    }
    @Test public void dismissedNoticeDoesNotReappearOnActivityRecreation(){
        open(true);dismissNotice();Bundle saved=new Bundle();c.saveInstanceState(saved).pause().stop().destroy();c=Robolectric.buildActivity(MainActivity.class).create(saved).start().resume().visible();h=c.get();ShadowLooper.idleMainLooper();assertNull(h.freeNotice);
    }
    @Test public void aboutIsLastSettingsEntryForBothBrandsAndHelpDoesNotResetOnboarding(){
        RuntimeEnvironment.getApplication().getSharedPreferences("console",0).edit().putBoolean("hide_free_notice",true).commit();open(true);
        for(LampBrand brand:LampBrand.values()){
            h.selectBrand(brand);h.showTab(2);View last=h.body.getChildAt(h.body.getChildCount()-1);TextView about=find(last,"关于软件  →");assertNotNull(brand.title,about);about.performClick();ShadowLooper.idleMainLooper();View dialog=h.infoDialog.getWindow().getDecorView();assertNotNull(find(dialog,"关于软件"));assertNotNull(find(dialog,"mosila console"));assertNotNull(find(dialog,MainActivity.FREE_NOTICE_TEXT));assertNotNull(find(dialog,"版本 "+h.appVersion()));
            TextView project=find(dialog,"https://github.com/SuancaiyuPro/mosila-console");assertNotNull(brand.title,project);project.performClick();android.content.Intent opened=Shadows.shadowOf(h).getNextStartedActivity();assertEquals(android.content.Intent.ACTION_VIEW,opened.getAction());assertEquals(MainActivity.PROJECT_URL,opened.getDataString());
            h.infoDialog.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();ShadowLooper.idleMainLooper();assertNotNull(find(h.infoDialog.getWindow().getDecorView(),"使用引导"));assertFalse(h.needsOnboarding());assertEquals(brand,h.activeBrand);assertFalse(h.ble.isScanning());h.infoDialog.dismiss();
        }
    }
}
