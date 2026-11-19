package com.fongmi.android.tv.ui.dialog;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Environment;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterPhpFolderBinding;
import com.fongmi.android.tv.databinding.DialogPhpFolderBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * 简易文件夹选择器（基于 java.io.File，应用已申请全部文件访问权限）。
 */
public class PhpFolderDialog extends DialogFragment {

    private DialogPhpFolderBinding binding;
    private FolderAdapter adapter;
    private File current;
    private Consumer<String> callback;

    public static PhpFolderDialog create() {
        return new PhpFolderDialog();
    }

    public PhpFolderDialog folder(String path) {
        this.current = new File(path == null || path.isEmpty() ? defaultStart() : path);
        if (!current.isDirectory() || !current.canRead()) current = new File(defaultStart());
        if (!current.isDirectory()) current = File.listRoots().length > 0 ? File.listRoots()[0] : new File("/");
        return this;
    }

    private static String defaultStart() {
        try {
            File ext = Environment.getExternalStorageDirectory();
            if (ext != null && ext.isDirectory()) return ext.getAbsolutePath();
        } catch (Exception ignored) {
        }
        return "/";
    }

    public PhpFolderDialog callback(Consumer<String> callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), "php-folder");
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        binding = DialogPhpFolderBinding.inflate(LayoutInflater.from(requireActivity()));
        if (current == null) current = new File("/");
        adapter = new FolderAdapter();
        binding.folderList.setLayoutManager(new LinearLayoutManager(requireActivity()));
        binding.folderList.setAdapter(adapter);
        binding.folderUp.setOnClickListener(v -> up());
        binding.folderSelect.setOnClickListener(v -> select());
        load(current);
        return new MaterialAlertDialogBuilder(requireActivity()).setView(binding.getRoot()).create();
    }

    private void load(File dir) {
        current = dir;
        binding.folderPath.setText(dir.getAbsolutePath());
        List<File> dirs = new ArrayList<>();
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) if (f.isDirectory() && !f.isHidden()) dirs.add(f);
            dirs.sort(Comparator.comparing(f -> f.getName().toLowerCase()));
        }
        adapter.set(dirs);
        binding.folderUp.setEnabled(dir.getParentFile() != null);
    }

    private void up() {
        if (current.getParentFile() != null) load(current.getParentFile());
    }

    private void select() {
        if (callback != null) callback.accept(current.getAbsolutePath());
        dismiss();
    }

    private class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.ViewHolder> {

        private final List<File> items = new ArrayList<>();

        void set(List<File> dirs) {
            items.clear();
            items.addAll(dirs);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterPhpFolderBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            File dir = items.get(position);
            holder.binding.folderName.setText(dir.getName());
            holder.itemView.setOnClickListener(v -> load(dir));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            final AdapterPhpFolderBinding binding;

            ViewHolder(AdapterPhpFolderBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}
