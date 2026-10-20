package com.suancaiyu.mosilalight;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.util.*;

/** A dark, centered wheel. Only intentional scrolling or tapping applies a mode. */
final class ModePicker extends Dialog {
    final MainActivity host;
    final List<String> labels;
    final java.util.function.IntSupplier current;
    final java.util.function.IntPredicate apply;
    final ArrayList<Integer> matches=new ArrayList<>();
    EditText search;
    ListView list;
    TextView count,status,empty;
    LinearLayout content;
    BaseAdapter adapter;
    boolean scrolling;
    int rowHeight, highlighted=-1;
    ModePicker(MainActivity host){this(host,host.modes,()->host.mode,id->{host.mode=id;host.save();return host.send(Protocol.mode(id,host.speed,host.modeA,host.modeB),true,false)&&host.lastSubmissionQueued;});}
    ModePicker(MainActivity host,List<String> labels,java.util.function.IntSupplier current,java.util.function.IntPredicate apply){super(host);this.host=host;this.labels=labels;this.current=current;this.apply=apply;}
    @Override protected void onCreate(Bundle state){
        super.onCreate(state);requestWindowFeature(Window.FEATURE_NO_TITLE);
        rowHeight=host.dp(48);
        content=host.column();content.setPadding(host.dp(20),host.dp(20),host.dp(20),host.dp(24));
        content.setBackground(host.shape(MainActivity.CARD,24,MainActivity.BORDER));
        LinearLayout title=host.row();title.setGravity(Gravity.CENTER_VERTICAL);
        title.addView(host.text("选择灯效",22,MainActivity.TEXT,true),new LinearLayout.LayoutParams(0,-2,1));
        title.addView(host.button("完成",false,v->dismiss()));content.addView(title);
        TextView hint=host.text(host.isOfflineDraft()?"上下滚动选择，确认后保存草稿":"上下滚动选择 · 已连接时立即应用",12,MainActivity.MUTED,false);
        hint.setPadding(0,host.dp(10),0,host.dp(16));content.addView(hint);
        search=new EditText(host);search.setSingleLine(true);search.setTextSize(15);
        search.setTextColor(MainActivity.TEXT);search.setHintTextColor(MainActivity.MUTED);
        search.setHint("搜索中文名称或编号");search.setContentDescription("搜索灯效");
        search.setBackground(host.shape(MainActivity.BG,14,MainActivity.BORDER));
        search.setPadding(host.dp(14),0,host.dp(14),0);
        content.addView(search,new LinearLayout.LayoutParams(-1,host.dp(48)));
        count=host.text("",11,MainActivity.MUTED,false);count.setPadding(0,host.dp(12),0,host.dp(8));content.addView(count);
        FrameLayout wheel=new FrameLayout(host);wheel.setBackground(host.shape(MainActivity.BG,18,0));
        View band=new View(host);band.setBackground(host.shape(0xff243022,12,0xff435538));
        FrameLayout.LayoutParams bp=new FrameLayout.LayoutParams(-1,rowHeight,Gravity.CENTER);bp.setMargins(host.dp(6),0,host.dp(6),0);wheel.addView(band,bp);
        list=new ListView(host);list.setDivider(null);list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setCacheColorHint(Color.TRANSPARENT);list.setVerticalScrollBarEnabled(false);list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        View top=new View(host);top.setLayoutParams(new AbsListView.LayoutParams(-1,rowHeight*2));list.addHeaderView(top,null,false);
        View bottom=new View(host);bottom.setLayoutParams(new AbsListView.LayoutParams(-1,rowHeight*2));list.addFooterView(bottom,null,false);
        adapter=new BaseAdapter(){
            public int getCount(){return matches.size();}
            public Integer getItem(int p){return matches.get(p);}
            public long getItemId(int p){return matches.get(p);}
            public boolean hasStableIds(){return true;}
            public View getView(int p,View recycled,ViewGroup parent){
                TextView t=recycled instanceof TextView?(TextView)recycled:host.text("",16,MainActivity.TEXT,false);
                t.setGravity(Gravity.CENTER);t.setSingleLine(true);t.setEllipsize(TextUtils.TruncateAt.END);
                t.setPadding(host.dp(12),0,host.dp(12),0);t.setLayoutParams(new AbsListView.LayoutParams(-1,rowHeight));
                int id=matches.get(p);t.setTag(id);t.setText(String.format(Locale.ROOT,"%03d   %s",id+1,labels.get(id)));
                style(t,id==highlighted);return t;
            }
        };
        list.setAdapter(adapter);wheel.addView(list,new FrameLayout.LayoutParams(-1,-1));
        empty=host.text("没有找到对应灯效\n试试名称片段或编号",14,MainActivity.MUTED,false);empty.setGravity(Gravity.CENTER);empty.setBackground(host.shape(MainActivity.BG,18,0));
        wheel.addView(empty,new FrameLayout.LayoutParams(-1,-1));content.addView(wheel,new LinearLayout.LayoutParams(-1,rowHeight*5));
        status=host.text("",12,MainActivity.MUTED,false);status.setPadding(0,host.dp(14),0,0);content.addView(status);
        list.setOnScrollListener(new AbsListView.OnScrollListener(){
            public void onScrollStateChanged(AbsListView v,int state){
                if(state==SCROLL_STATE_TOUCH_SCROLL)scrolling=true;
                if(state==SCROLL_STATE_IDLE && scrolling){scrolling=false;selectPosition(centerPosition());}
            }
            public void onScroll(AbsListView v,int first,int visible,int total){
                if(scrolling){int p=centerPosition();if(p>=0)highlight(matches.get(p));}
            }
        });
        list.setOnItemClickListener((parent,v,position,id)->{scrolling=false;selectPosition(position-1);});
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){filter(s.toString());}
            public void afterTextChanged(Editable s){}
        });
        setContentView(content);content.setFocusableInTouchMode(true);content.requestFocus();filter("");
        Window w=getWindow();w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);w.setDimAmount(.65f);
        w.setGravity(Gravity.CENTER);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN|WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        updateStatus(false);
    }
    @Override public void show(){super.show();getWindow().setLayout(Math.min(host.getResources().getDisplayMetrics().widthPixels-host.dp(24),host.dp(440)),WindowManager.LayoutParams.WRAP_CONTENT);}
    void filter(String query){
        scrolling=false;list.smoothScrollBy(0,0);
        matches.clear();matches.addAll(ModeSearch.find(labels,query));highlighted=current.getAsInt();
        adapter.notifyDataSetChanged();empty.setVisibility(matches.isEmpty()?View.VISIBLE:View.GONE);
        count.setText(matches.size()+" / "+labels.size()+" 个灯效");
        int selected=matches.indexOf(current.getAsInt());center(Math.max(0,selected));
    }
    int centerPosition(){
        if(matches.isEmpty())return -1;
        int best=-1;float distance=Float.MAX_VALUE;
        for(int i=0;i<list.getChildCount();i++){
            int pos=list.getFirstVisiblePosition()+i-1;if(pos<0||pos>=matches.size())continue;
            View child=list.getChildAt(i);float d=Math.abs((child.getTop()+child.getBottom())/2f-list.getHeight()/2f);
            if(d<distance){distance=d;best=pos;}
        }
        return best;
    }
    void center(int position){if(!matches.isEmpty())list.setSelectionFromTop(position+1,rowHeight*2);}
    void highlight(int id){
        highlighted=id;
        for(int i=0;i<list.getChildCount();i++){
            View v=list.getChildAt(i);if(v instanceof TextView)style((TextView)v,Objects.equals(v.getTag(),id));
        }
    }
    void style(TextView t,boolean selected){t.setTextColor(selected?MainActivity.ACCENT:MainActivity.MUTED);t.setTypeface(Typeface.DEFAULT,selected?Typeface.BOLD:Typeface.NORMAL);}
    void selectPosition(int position){
        if(!isShowing()||host.paused||position<0||position>=matches.size())return;
        int id=matches.get(position);
        boolean sent=apply.test(id);
        highlight(id);center(position);updateStatus(sent&&host.lastSubmissionQueued);
    }
    void updateStatus(boolean sent){
        status.setText((host.isOfflineDraft()?"草稿已修改，待确认 · ":sent?"已发送 · ":host.ble.readyCount()==0?"未连接，仅预览选择 · ":"当前选择 · ")+String.format(Locale.ROOT,"%03d %s",current.getAsInt()+1,labels.get(current.getAsInt())));
        status.setTextColor(sent?MainActivity.ACCENT:MainActivity.MUTED);
    }
    @Override public void dismiss(){scrolling=false;super.dismiss();}
}
