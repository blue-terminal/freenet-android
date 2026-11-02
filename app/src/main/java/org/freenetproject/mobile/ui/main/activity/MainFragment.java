package org.freenetproject.mobile.ui.main.activity;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import org.freenetproject.mobile.BuildConfig;
import org.freenetproject.mobile.R;
import org.freenetproject.mobile.services.node.Manager;
import org.freenetproject.mobile.ui.about.activity.AboutActivity;
import org.freenetproject.mobile.ui.bootstrap.activity.BootstrapActivity;
import org.freenetproject.mobile.ui.acknowledgement.activity.AcknowledgementActivity;
import org.freenetproject.mobile.ui.acknowledgement.activity.AcknowledgementFragment;
import org.freenetproject.mobile.ui.main.viewmodel.MainViewModel;
import org.freenetproject.mobile.ui.settings.activity.SettingsActivity;
import org.freenetproject.mobile.ui.sites.SitesActivity;

import org.json.JSONException;

import java.io.IOException;
import java.net.URLEncoder;
import java.security.NoSuchAlgorithmException;

public class MainFragment extends Fragment {
    private static final String PC_COMMAND = "adb forward tcp:7509 tcp:7509";
    private static final String RIVER_JOINED = "river_joined";
    private static final String RIVER_URL = Manager.WEB_URL
            + "v1/contract/web/raAqMhMG7KUpXBU2SxgCQ3Vh4PYjttxdSWd9ftV7RLv/";

    private static int actionIcon(Manager.Status status) {
        switch (status) {
            case STARTED:
            case PAUSED:
                return R.drawable.ic_baseline_power_settings_new_24;
            case ERROR:
                return R.drawable.ic_baseline_replay_24;
            default:
                return R.drawable.ic_baseline_play_circle_outline_24;
        }
    }

    private int statusColor(Manager.Status status) {
        int color;
        switch (status) {
            case STARTED:
                TypedValue accent = new TypedValue();
                requireContext().getTheme().resolveAttribute(
                        androidx.appcompat.R.attr.colorAccent, accent, true);
                return accent.data;
            case ERROR:
                color = R.color.colorError;
                break;
            case PAUSED:
            case STARTING_UP:
                color = R.color.colorWarning;
                break;
            default:
                color = android.R.color.white;
                break;
        }
        return requireContext().getColor(color);
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState
    ) {
        SharedPreferences prefs = getContext().getSharedPreferences(
                getContext().getPackageName(), Context.MODE_PRIVATE
        );

        if (!prefs.getBoolean(AcknowledgementFragment.ACKNOWLEDGEMENT_KEY, false)) {
            startActivity(new Intent(getContext(), AcknowledgementActivity.class));
        }

        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.fragment_main, container, false);
    }

    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        new ViewModelProvider(requireActivity()).get(MainViewModel.class);

        Manager manager = Manager.getInstance();
        updateSharedPreferences(manager, view);
        updateSettings(manager, view);
        updateAbout(manager, view);
        view.findViewById(R.id.pc_button).setOnClickListener(v -> showPcHelp());
        Button river = view.findViewById(R.id.river_button);
        river.setOnClickListener(v -> openRiver(river));
        manager.getStatus().observe(getViewLifecycleOwner(), status ->
                river.setEnabled(status.equals(Manager.Status.STARTED)));
        Button web = view.findViewById(R.id.web_button);
        web.setOnClickListener(v -> startActivity(new Intent(getContext(), BootstrapActivity.class)));
        manager.getStatus().observe(getViewLifecycleOwner(), status ->
                web.setEnabled(status.equals(Manager.Status.STARTED)));
        view.findViewById(R.id.sites_button).setOnClickListener(v ->
                startActivity(new Intent(getActivity(), SitesActivity.class)));
        updateControls(manager, view);
        updateStatus(manager, view);
        updateStatusDetail(manager, view);
    }

    /** The first time it joins the public room with an invite; then it just opens River. */
    private void openRiver(Button river) {
        SharedPreferences prefs = requireContext().getSharedPreferences(
                requireContext().getPackageName(), Context.MODE_PRIVATE);
        if (prefs.getBoolean(RIVER_JOINED, false)) {
            showRiver(RIVER_URL);
            return;
        }
        river.setEnabled(false);
        Toast.makeText(getContext(), R.string.river_joining, Toast.LENGTH_LONG).show();
        new Thread(() -> {
            String url = null;
            String problem = null;
            try {
                url = RIVER_URL + "?invitation=" + URLEncoder.encode(RiverInvite.request(), "UTF-8");
            } catch (IOException | JSONException | NoSuchAlgorithmException e) {
                problem = e.getMessage();
            }
            String invited = url;
            String failure = problem;
            river.post(() -> {
                river.setEnabled(Manager.getInstance().isRunning());
                if (!isAdded()) {
                    return;
                }
                if (invited == null) {
                    Toast.makeText(getContext(), getString(R.string.river_join_failed, failure),
                            Toast.LENGTH_LONG).show();
                    showRiver(RIVER_URL); // River still opens, without joining the room
                    return;
                }
                prefs.edit().putBoolean(RIVER_JOINED, true).apply();
                showRiver(invited);
            });
        }, "freenet-river-invite").start();
    }

    /** Through the USB cable only: the node has no password, so it must not reach the network. */
    private void showPcHelp() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.use_from_pc)
                .setMessage(getString(R.string.pc_help, PC_COMMAND, Manager.WEB_URL))
                .setNegativeButton(android.R.string.ok, null)
                .setPositiveButton(R.string.pc_copy, (d, w) ->
                        requireContext().getSystemService(ClipboardManager.class)
                                .setPrimaryClip(ClipData.newPlainText("adb", PC_COMMAND)))
                .show();
    }

    private void showRiver(String url) {
        startActivity(new Intent(getContext(), BootstrapActivity.class)
                .putExtra(BootstrapActivity.EXTRA_URL, url));
    }

    private void updateControls(Manager m, View view) {
        Button controlButton = view.findViewById(R.id.control_button);
        controlButton.setOnClickListener(view1 -> {
            // Nothing to do while it is still starting up or stopping.
            if (m.isTransitioning()) {
                return;
            }

            if (m.isRunning() || m.isPaused()) {
                m.stopService(view.getContext());
            } else {
                // After an error the service is still up, so it would not start the node again.
                m.startService(view.getContext());
                if (m.hasError()) {
                    Context appContext = view.getContext().getApplicationContext();
                    new Thread(() -> m.startNode(appContext), "freenet-start").start();
                }
            }
        });

        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            controlButton.setEnabled(!m.isTransitioning());
            controlButton.setBackgroundResource(actionIcon(status));
        });
    }

    private void updateStatus(Manager m, View view) {
        TextView statusText = view.findViewById(R.id.node_status);
        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            statusText.setTextColor(statusColor(status));
            if (status.equals(Manager.Status.STARTED)) {
                statusText.setText(R.string.node_running);
            } else if (status.equals(Manager.Status.STARTING_UP)) {
                statusText.setText(R.string.node_starting_up);
            } else if (status.equals(Manager.Status.PAUSED)) {
                statusText.setText(R.string.node_paused);
            } else if (status.equals(Manager.Status.STOPPING)) {
                statusText.setText(R.string.node_shutting_down);
            } else if (status.equals(Manager.Status.ERROR)) {
                statusText.setText(R.string.error_starting_up);
            } else {
                statusText.setText(getString(R.string.app_version, BuildConfig.VERSION_NAME));
            }
        });
    }

    private void updateStatusDetail(Manager m, View view) {
        TextView detailText = view.findViewById(R.id.node_status_detail);
        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            detailText.setOnClickListener(null);
            if (status.equals(Manager.Status.STARTED)) {
                detailText.setText(R.string.tap_to_navigate);
                detailText.setOnClickListener(view12 -> {
                   startActivity(
                           new Intent(getContext(), BootstrapActivity.class)
                   );
                });
            } else if (status.equals(Manager.Status.STARTING_UP)) {
                detailText.setText(R.string.may_take_a_while);
            } else if (status.equals(Manager.Status.PAUSED)) {
                detailText.setText(R.string.paused_detail);
            } else if (status.equals(Manager.Status.ERROR)) {
                String reason = m.lastLogLine();
                detailText.setText(reason.isEmpty()
                        ? getString(R.string.error_detail)
                        : getString(R.string.error_detail) + "\n" + reason);
            } else if (status.equals(Manager.Status.STOPPED)) {
                detailText.setText(R.string.press_to_start);
            } else {
                detailText.setText("");
            }
        });
    }

    private void updateSharedPreferences(Manager m, View view) {
        SharedPreferences sharedPref = requireActivity().getPreferences(Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPref.edit();

        m.getStatus().observe(getViewLifecycleOwner(), status -> {
            editor.putInt("status", status.ordinal());
            editor.apply();
        });
    }

    private void updateSettings(Manager m, View view) {
        Button settings = view.findViewById(R.id.settings_button);
        settings.setOnClickListener(view1 -> {
            startActivity(new Intent(getActivity(), SettingsActivity.class));
        });
    }

    private void updateAbout(Manager m, View view) {
        Button settings = view.findViewById(R.id.about_button);
        settings.setOnClickListener(view1 -> {
            startActivity(new Intent(getActivity(), AboutActivity.class));
        });
    }
}