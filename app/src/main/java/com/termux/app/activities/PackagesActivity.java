package com.termux.app.activities;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.termux.R;
import com.termux.shared.logger.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lists the packages of the NuxDroid repository (GitHub Pages) and installs the
 * chosen one through a real terminal session, so the screen never pretends to
 * manage packages on its own.
 *
 * <p/>
 * The index is the Debian {@code Packages} file of the same repo the prefix's
 * apt uses, which means what is shown here is exactly what {@code apt} can
 * install.
 */
public class PackagesActivity extends AppCompatActivity {

    private static final String LOG_TAG = "PackagesActivity";

    /** Same repo as {@code sources.list}; binary-aarch64 first, then the plain layout. */
    private static final String[] INDEX_URLS = {
        "https://mrzgamingv20-cyber.github.io/nuxdroid-packages/dists/aarch64/main/binary-aarch64/Packages",
        "https://mrzgamingv20-cyber.github.io/nuxdroid-packages/dists/aarch64/main/binary/Packages",
    };

    private final List<RepoPackage> mPackages = new ArrayList<>();
    private PackagesAdapter mAdapter;
    private TextView mStatusView;
    private Button mRetryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_packages);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.dashboard_packages_title);
        }

        mStatusView = findViewById(R.id.packages_status);
        mRetryButton = findViewById(R.id.packages_retry);
        ListView listView = findViewById(R.id.packages_list);

        mAdapter = new PackagesAdapter();
        listView.setAdapter(mAdapter);
        listView.setOnItemClickListener((parent, view, position, id) -> confirmInstall(mPackages.get(position)));
        mRetryButton.setOnClickListener(view -> load());

        // Shared bottom bar: mark Packages as the current destination.
        BottomNavigationView bottomNav = findViewById(R.id.dashboard_bottom_nav);
        DashboardNav.bind(this, bottomNav, R.id.nav_packages);

        load();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish(); // Back to the dashboard.
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** Fetch the package index on a background thread, then refresh the list. */
    private void load() {
        mPackages.clear();
        mAdapter.notifyDataSetChanged();
        mStatusView.setVisibility(View.VISIBLE);
        mStatusView.setText(R.string.packages_loading);
        mRetryButton.setVisibility(View.GONE);

        new Thread(() -> {
            List<RepoPackage> loaded = null;
            String error = null;
            for (String indexUrl : INDEX_URLS) {
                try {
                    loaded = parsePackages(fetch(indexUrl));
                    error = null;
                    break;
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "Fetching " + indexUrl + " failed: " + e.getMessage());
                    error = e.getMessage();
                }
            }

            final List<RepoPackage> result = loaded;
            final String failure = error;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result == null || result.isEmpty()) {
                    mStatusView.setText(result == null
                        ? getString(R.string.packages_load_failed, failure == null ? "?" : failure)
                        : getString(R.string.packages_empty));
                    mRetryButton.setVisibility(View.VISIBLE);
                    return;
                }
                mPackages.addAll(result);
                mAdapter.notifyDataSetChanged();
                mStatusView.setVisibility(View.GONE);
            });
        }, "nuxdroid-package-index").start();
    }

    /** Ask before installing: the command replaces the whole terminal view. */
    private void confirmInstall(RepoPackage pkg) {
        if (!isSafePackageName(pkg.name)) {
            Toast.makeText(this, getString(R.string.packages_name_invalid, pkg.name), Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle(R.string.packages_install_title)
            .setMessage(getString(R.string.packages_install_confirm, pkg.name, pkg.version))
            .setPositiveButton(R.string.packages_install_action, (dialog, which) ->
                // Always refresh the index first: a stale index points at deleted files.
                TerminalCommands.run(this, "apt-get update && apt-get install -y " + pkg.name))
            .setNegativeButton(android.R.string.cancel, null)
            .show();
    }

    /** Package names only: no shell metacharacters can ever reach the terminal. */
    private static boolean isSafePackageName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean allowed = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || c == '.' || c == '+' || c == '-';
            if (!allowed) return false;
        }
        return true;
    }

    private static String fetch(String indexUrl) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(indexUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(15000);
        connection.setInstanceFollowRedirects(true);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code + " for " + indexUrl);
            }
            try (BufferedReader reader =
                     new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder out = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
                return out.toString();
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Parse the Debian {@code Packages} index: blank-line separated {@code Key: value} blocks. */
    private static List<RepoPackage> parsePackages(String index) {
        List<RepoPackage> packages = new ArrayList<>();
        RepoPackage current = null;
        for (String rawLine : index.split("\n")) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty()) {
                if (current != null && current.name != null) packages.add(current);
                current = null;
                continue;
            }
            if (line.startsWith(" ") || line.startsWith("\t")) continue; // description continuation
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon);
            String value = line.substring(colon + 1).trim();
            if ("Package".equals(key)) {
                if (current != null && current.name != null) packages.add(current);
                current = new RepoPackage();
                current.name = value;
            } else if (current != null) {
                if ("Version".equals(key)) {
                    current.version = value;
                } else if ("Description".equals(key)) {
                    current.description = value;
                }
            }
        }
        if (current != null && current.name != null) packages.add(current);
        return packages;
    }

    /** One entry of the Debian Packages index, only the fields the list shows. */
    private static final class RepoPackage {
        String name;
        String version = "";
        String description = "";
    }

    private class PackagesAdapter extends BaseAdapter {

        @Override
        public int getCount() {
            return mPackages.size();
        }

        @Override
        public Object getItem(int position) {
            return mPackages.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.item_package, parent, false);
            }
            RepoPackage pkg = mPackages.get(position);

            ((TextView) row.findViewById(R.id.packages_item_name)).setText(pkg.name);
            ((TextView) row.findViewById(R.id.packages_item_version)).setText(pkg.version);

            TextView description = row.findViewById(R.id.packages_item_description);
            description.setText(pkg.description);
            description.setVisibility(pkg.description.isEmpty() ? View.GONE : View.VISIBLE);

            Button install = row.findViewById(R.id.packages_item_install);
            install.setOnClickListener(view -> confirmInstall(pkg));
            return row;
        }
    }

}
