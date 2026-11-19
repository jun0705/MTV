package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogPhpBinding;
import com.fongmi.android.tv.server.PhpServer;
import com.fongmi.android.tv.setting.PhpSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PhpAssets;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * PHP 设置弹窗：开关、端口、地址复制、默认目录及自定义目录。
 * 涉及文件访问的操作均先申请存储权限。
 *
 * <p>开关状态以服务真实运行状态（{@link PhpServer#isRunning()}）为准，
 * 避免持久化开关与进程实际状态不同步造成“开关不灵”。
 * 服务启停（含首次解压 runtime、NanoHTTPD 的 join/bind）一律放到后台线程执行，
 * 避免阻塞主线程造成首次开启卡顿。</p>
 */
public class PhpDialog extends DialogFragment {

    private DialogPhpBinding binding;
    // 程序化 setChecked 时置 true，避免误触发 OnCheckedChangeListener
    private boolean syncing;
    // 服务状态变化回调：通知宿主刷新设置列表右侧的“开/关”
    private Runnable onStateChange;

    public static PhpDialog create() {
        return new PhpDialog();
    }

    public PhpDialog callback(Runnable callback) {
        this.onStateChange = callback;
        return this;
    }

    private void refreshHost() {
        if (onStateChange != null) onStateChange.run();
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), "php");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogPhpBinding.inflate(LayoutInflater.from(requireActivity()));
        initView();
        initEvent();
        return new MaterialAlertDialogBuilder(requireActivity()).setView(binding.getRoot()).create();
    }

    private void initView() {
        binding.phpPort.setText(String.valueOf(PhpSetting.getPort()));
        binding.phpRoot.setText(PhpSetting.getRoot());
        updateAddress();
        // 开关状态反映服务真实运行状态；若配置为开但服务未运行，尝试恢复（需权限）
        boolean wantOn = PhpSetting.isEnabled();
        syncChecked(wantOn && PhpServer.get().isRunning());
        PermissionUtil.requestFile(requireActivity(), granted -> {
            if (!granted || !isAdded()) return;
            PhpSetting.ensureDefaultRoot();
            PhpAssets.installSites();
            App.post(() -> {
                if (isAdded()) binding.phpRoot.setText(PhpSetting.getRoot());
            });
            // 配置为开但服务没在跑：有权限后自动恢复，保证开关与状态一致
            if (wantOn && !PhpServer.get().isRunning()) {
                startAsync();
            }
        });
    }

    /** 只更新开关显示，不触发监听器 */
    private void syncChecked(boolean checked) {
        syncing = true;
        binding.phpSwitch.setChecked(checked);
        syncing = false;
    }

    private int parsePort(String text, int fallback) {
        try {
            int p = Integer.parseInt(text.trim());
            return p > 1024 && p < 65536 ? p : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 校验并保存端口。只要输入框里的端口与已保存端口不同就写入并重启服务，
     * 不再依赖 isRunning()：这样即使服务当前未运行，改端口也会被保存，
     * 下次开启直接生效；服务正在运行时则立即用新端口重启。
     */
    private void commitPort() {
        if (!isAdded()) return;
        int p = parsePort(binding.phpPort.getText().toString(), PhpSetting.getPort());
        binding.phpPort.setText(String.valueOf(p));
        binding.phpAddress.setText("http://" + Util.getIp() + ":" + p + "/");
        if (p == PhpSetting.getPort()) return;
        PhpSetting.putPort(p);
        if (PhpServer.get().isRunning()) withPermission(this::restartAsync);
        else updateAddress();
    }

    /** 后台启动服务，完成后回主线程同步开关、地址与宿主状态。 */
    private void startAsync() {
        Task.execute(() -> {
            PhpServer.get().start(App.get());
            App.post(this::syncUi);
        });
    }

    /** 后台停止服务，完成后回主线程同步。 */
    private void stopAsync() {
        Task.execute(() -> {
            PhpServer.get().stop();
            App.post(this::syncUi);
        });
    }

    /** 后台用当前（最新）端口重启服务，完成后回主线程同步。 */
    private void restartAsync() {
        Task.execute(() -> {
            PhpServer.get().restart(App.get());
            App.post(this::syncUi);
        });
    }

    private void syncUi() {
        if (!isAdded()) return;
        syncChecked(PhpServer.get().isRunning());
        updateAddress();
        refreshHost();
    }

    private void updateAddress() {
        binding.phpAddress.setText(PhpServer.get().getAddress());
    }

    /** 需要文件访问权限才能执行的操作统一走这里 */
    private void withPermission(Runnable action) {
        PermissionUtil.requestFile(requireActivity(), granted -> {
            if (granted) App.post(action);
            else {
                Notify.show(R.string.php_no_permission);
                App.post(() -> {
                    if (!isAdded()) return;
                    syncChecked(false);
                    PhpSetting.putEnabled(false);
                });
            }
        });
    }

    private void initEvent() {
        binding.phpSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncing) return;
            PhpSetting.putEnabled(checked);
            if (checked) withPermission(this::startAsync);
            else stopAsync();
        });
        // 复制取地址文本框的实时内容：先提交端口再复制，保证端口与地址一致
        binding.phpCopy.setOnClickListener(v -> {
            commitPort();
            Util.copy(binding.phpAddress.getText().toString());
        });
        binding.phpAddress.setOnClickListener(v -> {
            commitPort();
            Util.copy(binding.phpAddress.getText().toString());
        });
        // 端口输入时实时联动地址；离开输入框或按回车时校验并保存，运行中则重启服务
        binding.phpPort.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) {
                int p = parsePort(s.toString(), PhpSetting.getPort());
                binding.phpAddress.setText("http://" + Util.getIp() + ":" + p + "/");
            }
        });
        binding.phpPort.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) commitPort();
        });
        binding.phpPort.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commitPort();
                binding.phpPort.clearFocus();
                return true;
            }
            return false;
        });
        binding.phpChoose.setOnClickListener(v -> withPermission(() ->
                PhpFolderDialog.create().folder(PhpSetting.getRoot()).callback(this::setRoot).show(requireActivity())));
    }

    private void setRoot(String path) {
        PhpSetting.putRoot(path);
        binding.phpRoot.setText(path);
        if (PhpSetting.isEnabled()) withPermission(this::restartAsync);
    }

    @Override
    public void onStart() {
        super.onStart();
        syncChecked(PhpServer.get().isRunning());
        updateAddress();
        refreshHost();
    }

    @Override
    public void onDismiss(@NonNull DialogInterface dialog) {
        super.onDismiss(dialog);
        // 关闭弹窗时兜底提交端口，避免“改了端口直接关掉 → 没保存”的问题
        if (isAdded()) commitPort();
        refreshHost();
    }
}
