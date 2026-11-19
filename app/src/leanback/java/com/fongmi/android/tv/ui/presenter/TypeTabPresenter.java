package com.fongmi.android.tv.ui.presenter;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.databinding.AdapterTabSiteBinding;

import java.util.List;

/**
 * OK 影视风格首页顶部分类 Tab 栏的 Presenter。
 *
 * 数据源是首页 Result.getTypes()（Class 列表），首位额外插入一个「首页」Tab。
 * 带筛选条件的分类在名称后追加 ▼，与 OK 版一致。
 * 这里用全限定名引用 bean.Class，避免与 java.lang.Class 冲突。
 *
 * OK 影视版新增：长按「首页」Tab 触发 onHomeLongClick()（刷新接口）。
 * TV 遥控器长按表现为重复 ACTION_DOWN / FLAG_LONG_PRESS，
 * 单靠 OnLongClickListener 不一定触发，因此同时用 OnKeyListener 判定。
 */
public class TypeTabPresenter extends Presenter {

    public static final String HOME = "首页";

    private final OnClickListener listener;
    private final OnLongClickListener longListener;

    public TypeTabPresenter(OnClickListener listener, OnLongClickListener longListener) {
        this.listener = listener;
        this.longListener = longListener;
    }

    public interface OnClickListener {
        /** item 为 null 表示点的是「首页」Tab。 */
        void onItemClick(com.fongmi.android.tv.bean.Class item);
    }

    /** OK 影视版：长按「首页」Tab 刷新接口。 */
    public interface OnLongClickListener {
        void onHomeLongClick();
    }

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        AdapterTabSiteBinding binding = AdapterTabSiteBinding.inflate(
                LayoutInflater.from(parent.getContext()), parent, false);
        return new ViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object object) {
        ViewHolder holder = (ViewHolder) viewHolder;
        boolean isHome = object instanceof String;
        com.fongmi.android.tv.bean.Class item = isHome
                ? null : (com.fongmi.android.tv.bean.Class) object;
        String text;
        if (isHome) {
            text = HOME;
        } else {
            text = item.getTypeName();
            List<?> filters = item.getFilters();
            if (item.getFilter() || (filters != null && !filters.isEmpty())) text = text + " ▼";
        }
        holder.binding.text.setText(text);
        View root = holder.binding.getRoot();
        root.setSelected(isHome);
        setOnClickListener(holder, view -> listener.onItemClick(item));

        if (isHome) {
            final boolean[] longHandled = {false};
            final long[] lastRefresh = {0};
            root.setLongClickable(true);
            root.setOnLongClickListener(view -> {
                longListener.onHomeLongClick();
                return true;
            });
            root.setOnKeyListener((view, code, event) -> {
                if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER)
                    return false;
                int act = event.getAction();
                if (act == KeyEvent.ACTION_DOWN) {
                    boolean isLong = event.getRepeatCount() > 0
                            || (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
                    if (isLong) {
                        long now = System.currentTimeMillis();
                        if (now - lastRefresh[0] > 1500) {
                            lastRefresh[0] = now;
                            longListener.onHomeLongClick();
                        }
                        longHandled[0] = true;
                    }
                    return true; // 消费 DOWN，短按留到 UP 处理，避免长按误弹站点
                }
                if (act == KeyEvent.ACTION_UP) {
                    boolean handled = longHandled[0];
                    longHandled[0] = false;
                    if (!handled) listener.onItemClick(null);
                    return true;
                }
                return false;
            });
        } else {
            // ViewHolder 会复用，非首页项必须清掉长按监听
            root.setOnKeyListener(null);
            root.setOnLongClickListener(null);
            root.setLongClickable(false);
        }
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterTabSiteBinding binding;

        public ViewHolder(@NonNull AdapterTabSiteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
