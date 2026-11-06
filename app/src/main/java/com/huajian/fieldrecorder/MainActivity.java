package com.huajian.fieldrecorder;

import android.Manifest;
import android.app.AlertDialog;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.ComponentActivity;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.huajian.fieldrecorder.export.PacketExporter;
import com.huajian.fieldrecorder.asr.Pcm16StreamDecoder;
import com.huajian.fieldrecorder.asr.Qwen3RealtimeClient;
import com.huajian.fieldrecorder.security.ApiKeyVault;
import com.huajian.fieldrecorder.speaker.SpeakerFusion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native recorder: events do NOT disrupt the original CameraX audio/video. */
public class MainActivity extends ComponentActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SessionStore.Session session;
    private VideoCapture<Recorder> cameraVideo;
    private Recording currentRecording;
    private boolean recordingActive = false;
    private boolean busy = false;
    private boolean startRequested = false;
    private long lastReportedMs = 0L;
    private long lastStatusElapsedMs = 0L;
    private String pendingExternalOrigin;

    private View preparePanel, capturePanel, reviewPanel;
    private EditText participants;
    private CheckBox guardianConsent, adultConsent, childAssent;
    private TextView timer, lastEvent, reviewInfo, exportStatus;
    private LinearLayout eventList;
    private VideoView reviewVideo;
    private PreviewView cameraPreview;
    private ProgressBar spinner;
    private Button recordBtn, exportBtn, archiveBtn, stopBtn, recognizeBtn;
    private Spinner apiRegion;
    private EditText apiInput, apiWorkspace;
    private TextView apiStatus, speechStatus;
    private LinearLayout speechList;
    private CheckBox cloudConsent;
    private ActivityResultLauncher<Intent> speakerPicker;

    private ActivityResultLauncher<String[]> permissionLauncher;
    private ActivityResultLauncher<Intent> picker;
    private ActivityResultLauncher<Intent> sysCamera;
    private ActivityResultLauncher<Intent> artPicker;
    private ActivityResultLauncher<Intent> packetFolderPicker;
    private ActivityResultLauncher<Intent> archivePicker;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        setContentView(R.layout.activity_main);
        View contentRoot = findViewById(android.R.id.content);
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        bindViews();
        configureApiSettings();
        registerLaunchers();
        wireUi();
        showPage(0);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (recordingActive) {
                    new AlertDialog.Builder(MainActivity.this).setTitle("正在记录")
                        .setMessage("需要先结束录像，才能离开记录页。")
                        .setPositiveButton("结束录像", (d, w) -> stopVideo())
                        .setNegativeButton("继续记录", null).show();
                } else if (busy) {
                    Toast.makeText(MainActivity.this, "正在处理文件，请保持应用在前台", Toast.LENGTH_SHORT).show();
                } else { showPage(0); }
            }
        });
    }

    private void bindViews() {
        preparePanel = findViewById(R.id.panel_prepare);
        capturePanel = findViewById(R.id.panel_record);
        reviewPanel = findViewById(R.id.panel_review);
        participants = findViewById(R.id.participants);
        guardianConsent = findViewById(R.id.consent_guardian);
        adultConsent = findViewById(R.id.consent_adults);
        childAssent = findViewById(R.id.consent_child);
        timer = findViewById(R.id.record_timer);
        lastEvent = findViewById(R.id.last_event);
        cameraPreview = findViewById(R.id.camera_preview);
        reviewVideo = findViewById(R.id.review_video);
        reviewInfo = findViewById(R.id.review_info);
        eventList = findViewById(R.id.review_event_list);
        exportStatus = findViewById(R.id.export_status);
        spinner = findViewById(R.id.export_spinner);
        recordBtn = findViewById(R.id.btn_record);
        exportBtn = findViewById(R.id.btn_export);
        archiveBtn = findViewById(R.id.btn_archive);
        stopBtn = findViewById(R.id.btn_stop);
        apiRegion = findViewById(R.id.api_region);
        apiInput = findViewById(R.id.api_key_input);
        apiWorkspace = findViewById(R.id.api_workspace);
        apiStatus = findViewById(R.id.api_status);
        recognizeBtn = findViewById(R.id.btn_qwen_analyze);
        speechStatus = findViewById(R.id.speech_status);
        speechList = findViewById(R.id.speech_list);
        cloudConsent = findViewById(R.id.consent_cloud_asr);
    }

    private void configureApiSettings() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"中国内地 · 北京", "国际 · 新加坡"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        apiRegion.setAdapter(adapter);
        apiRegion.setSelection("singapore".equals(ApiKeyVault.region(this)) ? 1 : 0);
        apiWorkspace.setText(ApiKeyVault.workspace(this));
        showApiKeyStatus();
        findViewById(R.id.btn_api_save).setOnClickListener(v -> {
            if (recordingActive || busy) { alert("请结束当前记录或处理任务后再修改密钥"); return; }
            try {
                ApiKeyVault.save(this, apiInput.getText().toString(),
                        apiRegion.getSelectedItemPosition() == 1 ? "singapore" : "beijing",
                        apiWorkspace.getText().toString());
                apiInput.setText(""); // Do not keep secrets on screen.
                showApiKeyStatus();
                Toast.makeText(this, "API Key 已用 Android Keystore 加密保存", Toast.LENGTH_SHORT).show();
            } catch (Exception e) { alert("保存 API Key 失败：" + e.getMessage()); }
        });
        findViewById(R.id.btn_api_clear).setOnClickListener(v -> {
            if (recordingActive || busy) { alert("请结束当前记录或处理任务后再操作"); return; }
            ApiKeyVault.clear(this);
            apiInput.setText("");
            showApiKeyStatus();
        });
    }

    private void showApiKeyStatus() {
        apiStatus.setText(ApiKeyVault.hasKey(this)
                ? "✓ 已保存加密 Key（不会导出）。地域与 Workspace 在下一次保存 Key 时一起更新。"
                : "尚未保存 API Key；不影响录像和本地分包。请使用独立、可限制额度的测试 Key。");
    }

    private void registerLaunchers() {
        permissionLauncher = registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), results -> {
            if (Boolean.TRUE.equals(results.get(Manifest.permission.CAMERA)) &&
                Boolean.TRUE.equals(results.get(Manifest.permission.RECORD_AUDIO))) {
                openNativeCamera();
            } else { alert("需要摄像头和麦克风权限，才可以录制原声视频。"); }
        });
        picker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            Uri uri = result.getData().getData();
            if (uri != null) importVideo(uri);
        });
        sysCamera = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (session == null) return;
            if (result.getResultCode() != RESULT_OK) { showPage(0); return; }
            File dest = session.rawVideo();
            Uri selected = result.getData() == null ? null : result.getData().getData();
            if (dest.isFile() && dest.length() > 1024) {
                readyFromVideo("system_camera");
            } else if (selected != null) {
                work("正在导入系统相机视频…", () -> {
                    SessionStore.copyUri(this, selected, dest);
                    SessionStore.setVideoReady(session, "system_camera");
                }, this::enterReview);
            } else alert("系统相机没有返回视频。请改用“导入视频”。");
        });
        artPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || session == null) return;
            Uri uri = result.getData().getData();
            if (uri == null) return;
            work("正在保存作品照片…", () -> SessionStore.saveArt(this, uri, session), () -> {
                Toast.makeText(this, "已保存作品终稿", Toast.LENGTH_SHORT).show();
                enterReview();
            });
        });
        packetFolderPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || session == null) return;
            Uri tree = result.getData().getData();
            if (tree == null) return;
            try {
                getContentResolver().takePersistableUriPermission(tree,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (SecurityException ignored) { /* The immediate URI permission still works. */ }
            setBusy(true, "正在整理音轨和关键画面。录制越长，处理越久；请保持应用在前台。", true);
            io.execute(() -> {
                try {
                    PacketExporter.Result r = new PacketExporter().export(this, session, tree,
                        msg -> runOnUiThread(() -> exportStatus.setText(msg)));
                    runOnUiThread(() -> {
                        setBusy(false, "已保存 " + r.parts + " 个 ZIP。每包均 < 400,000,000 字节。\n输出文件夹中另含索引和校验值。", true);
                        new AlertDialog.Builder(this).setTitle("资料包导出成功")
                            .setMessage("生成 " + r.parts + " 个分包。请按编号提交；建议先用电脑校验工具检查完整性。\n\nZIP 中媒体能否被 GPT 直接解析取决于当前上传入口，必要时解压后上传 M4A / JPG / JSON。")
                            .setPositiveButton("好的", null).show();
                    });
                } catch (Exception e) { runOnUiThread(() -> {
                    setBusy(false, "导出失败：" + e.getMessage(), true);
                    alert("导出未完成：" + e.getMessage());
                }); }
            });
        });
        speakerPicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || session == null) return;
            Uri selected = result.getData().getData();
            if (selected == null) return;
            final SessionStore.Session current = session;
            work("正在验证本地说话人时间段…", () -> {
                File asr = new File(current.dir, "analysis/qwen3_segments.json");
                if (!asr.isFile()) throw new java.io.IOException("请先完成 Qwen3 语音识别");
                File selectedFile = new File(current.dir, "analysis/diarization_import.json");
                SessionStore.copyUri(this, selected, selectedFile);
                JSONObject data = SessionStore.readJsonObject(selectedFile);
                JSONObject merged = SpeakerFusion.merge(SessionStore.readJsonObject(asr), data);
                SessionStore.writeJson(new File(current.dir, "analysis/fused_timeline.json"), merged);
            }, () -> { renderSpeech(); Toast.makeText(this, "说话人时间段已合并；仍需人工核听", Toast.LENGTH_SHORT).show(); });
        });
        archivePicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null || session == null) return;
            Uri dst = result.getData().getData();
            if (dst == null) return;
            work("正在另存原始录像…", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(dst, "w")) {
                    if (out == null) throw new java.io.IOException("无法创建归档文件");
                    SessionStore.copyFile(session.rawVideo(), out);
                }
            }, () -> Toast.makeText(this, "原始视频已另存", Toast.LENGTH_SHORT).show());
        });
    }

    private void wireUi() {
        recognizeBtn.setOnClickListener(v -> requestQwenRecognition());
        findViewById(R.id.btn_import_speaker).setOnClickListener(v -> {
            if (busy || session == null) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            speakerPicker.launch(i);
        });
        recordBtn.setOnClickListener(v -> {
            if (!checkConsent()) return;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                openNativeCamera();
            } else permissionLauncher.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO});
        });
        findViewById(R.id.btn_system_camera).setOnClickListener(v -> {
            if (!checkConsent()) return;
            try {
                session = SessionStore.create(this, participants.getText().toString(), "system_camera");
                Uri out = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", session.rawVideo());
                Intent i = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
                i.putExtra(MediaStore.EXTRA_OUTPUT, out);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                sysCamera.launch(i);
            } catch (Exception e) { alert("无法启动系统相机：" + e.getMessage()); }
        });
        findViewById(R.id.btn_import).setOnClickListener(v -> {
            if (!checkConsent()) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/*");
            picker.launch(i);
        });
        findViewById(R.id.btn_resume).setOnClickListener(v -> {
            SessionStore.Session latest = SessionStore.latestReady(this);
            if (latest == null) alert("没有发现可继续的录像。");
            else { session = latest; enterReview(); }
        });
        final int[] ids = {R.id.event_0, R.id.event_1, R.id.event_2, R.id.event_3,
            R.id.event_4, R.id.event_5, R.id.event_6, R.id.event_7, R.id.event_8};
        for (int n = 0; n < ids.length; n++) {
            final String type = SessionStore.EVENT_TYPES[n];
            Button b = findViewById(ids[n]);
            b.setOnClickListener(v -> { if (recordingActive) addLiveEvent(type, "", liveMediaTimeMs()); });
            b.setOnLongClickListener(v -> {
                if (recordingActive) { askNote(type, liveMediaTimeMs(), "camera_recording_stats_estimate"); return true; }
                return false;
            });
        }
        stopBtn.setOnClickListener(v -> stopVideo());
        findViewById(R.id.btn_final_art).setOnClickListener(v -> {
            if (busy) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            artPicker.launch(i);
        });
        findViewById(R.id.btn_add_review_event).setOnClickListener(v -> {
            if (busy || session == null) return;
            String[] choices = SessionStore.EVENT_TYPES;
            new AlertDialog.Builder(this).setTitle("在 " + clock(reviewVideo.getCurrentPosition()) + " 补记")
                .setItems(choices, (dlg, which) -> askNote(choices[which], reviewVideo.getCurrentPosition(), "review_video_seek"))
                .show();
        });
        exportBtn.setOnClickListener(v -> {
            if (busy || session == null) return;
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            packetFolderPicker.launch(i);
        });
        archiveBtn.setOnClickListener(v -> {
            if (busy || session == null) return;
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("video/mp4");
            i.putExtra(Intent.EXTRA_TITLE, session.id() + "_ORIGINAL.mp4");
            archivePicker.launch(i);
        });
        findViewById(R.id.btn_delete_session).setOnClickListener(v -> {
            if (busy || recordingActive || session == null) return;
            final SessionStore.Session target = session;
            new AlertDialog.Builder(this).setTitle("永久删除本机记录？")
                .setMessage("将删除本机此会话的原始 MP4、事件、作品图及本地 ZIP，无法撤销。此前另存到系统文件夹的资料需另外删除。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (d, which) -> work("正在删除本机记录…",
                    () -> SessionStore.deleteSession(target), () -> {
                        session = null;
                        reviewVideo.stopPlayback();
                        showPage(0);
                        Toast.makeText(this, "本机此次记录已删除", Toast.LENGTH_SHORT).show();
                    })).show();
        });
        findViewById(R.id.btn_new).setOnClickListener(v -> {
            if (!busy) {
                session = null;
                guardianConsent.setChecked(false);
                adultConsent.setChecked(false);
                childAssent.setChecked(false);
                showPage(0);
            }
        });
    }

    private boolean checkConsent() {
        if (!(guardianConsent.isChecked() && adultConsent.isChecked() && childAssent.isChecked())) {
            alert("请先逐项确认监护人、现场成人和儿童的知情与同意。");
            return false;
        }
        try { SessionStore.parseParticipants(participants.getText().toString()); return true; }
        catch (Exception e) { alert(e.getMessage()); return false; }
    }

    private void openNativeCamera() {
        if (busy || startRequested || recordingActive) return;
        try {
            session = SessionStore.create(this, participants.getText().toString(), "camerax");
        } catch (Exception e) { alert(e.getMessage()); return; }
        startRequested = true;
        lastReportedMs = 0L; lastStatusElapsedMs = 0L;
        showPage(1);
        stopBtn.setEnabled(false);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        providerFuture.addListener(() -> {
            try {
                ProcessCameraProvider provider = providerFuture.get();
                provider.unbindAll();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(cameraPreview.getSurfaceProvider());
                Recorder recorder = new Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.HD))).build();
                cameraVideo = VideoCapture.withOutput(recorder);
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, cameraVideo);
                FileOutputOptions output = new FileOutputOptions.Builder(session.rawVideo()).build();
                PendingRecording pending = cameraVideo.getOutput().prepareRecording(this, output).withAudioEnabled();
                currentRecording = pending.start(ContextCompat.getMainExecutor(this), this::handleCameraEvent);
            } catch (Exception e) {
                startRequested = false;
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                alert("无法启动手机录像：" + e.getMessage());
                showPage(0);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void handleCameraEvent(VideoRecordEvent event) {
        if (event instanceof VideoRecordEvent.Start) {
            startRequested = false;
            recordingActive = true;
            lastReportedMs = 0L;
            lastStatusElapsedMs = SystemClock.elapsedRealtime();
            stopBtn.setEnabled(true);
            timer.setText("●  00:00");
        } else if (event instanceof VideoRecordEvent.Status) {
            lastReportedMs = event.getRecordingStats().getRecordedDurationNanos() / 1_000_000L;
            lastStatusElapsedMs = SystemClock.elapsedRealtime();
            timer.setText("●  " + clock(lastReportedMs));
        } else if (event instanceof VideoRecordEvent.Finalize) {
            recordingActive = false;
            startRequested = false;
            currentRecording = null;
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            VideoRecordEvent.Finalize fin = (VideoRecordEvent.Finalize) event;
            if (fin.hasError()) {
                alert("录像意外中止（错误码 " + fin.getError() + "）。如存在完整视频，可通过“继续上一次记录”尝试恢复。");
                showPage(0);
                return;
            }
            readyFromVideo("camerax");
        }
    }

    private void stopVideo() {
        if (currentRecording == null || !recordingActive) return;
        stopBtn.setEnabled(false);
        stopBtn.setText("正在完成录像…");
        currentRecording.stop();
    }

    private void readyFromVideo(String kind) {
        try {
            SessionStore.setVideoReady(session, kind);
            enterReview();
        } catch (Exception e) {
            alert("视频保存后校验失败：" + e.getMessage());
            showPage(0);
        }
    }

    private void importVideo(Uri uri) {
        try { session = SessionStore.create(this, participants.getText().toString(), "gallery_import"); }
        catch (Exception e) { alert(e.getMessage()); return; }
        work("正在导入相册视频…", () -> {
            SessionStore.copyUri(this, uri, session.rawVideo());
            SessionStore.setVideoReady(session, "gallery_import");
        }, this::enterReview);
    }

    private long liveMediaTimeMs() {
        if (lastStatusElapsedMs == 0L) return 0;
        // Anchored to CameraX RecordingStats, not Activity launch time.
        long lag = Math.max(0L, SystemClock.elapsedRealtime() - lastStatusElapsedMs);
        return Math.max(0, lastReportedMs + Math.min(2_000L, lag));
    }

    private void addLiveEvent(String type, String note, long t) {
        if (session == null) return;
        try {
            SessionStore.addEvent(session, type, t, note, "camera_recording_stats_estimate");
            lastEvent.setText("✓  " + clock(t) + "  " + type);
        } catch (Exception e) { alert("事件未保存：" + e.getMessage()); }
    }

    private void askNote(String type, long atMs, String basis) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setHint("只描述当场看到或听到的事实，不推断心理诊断");
        input.setMinLines(2);
        new AlertDialog.Builder(this)
            .setTitle(type + " · " + clock(atMs))
            .setView(input)
            .setPositiveButton("保存时刻", (d, w) -> {
                try {
                    SessionStore.addEvent(session, type, atMs, input.getText().toString(), basis);
                    if (recordingActive) lastEvent.setText("✓  " + clock(atMs) + "  " + type + " · 已备注");
                    else renderEvents();
                } catch (Exception e) { alert("事件保存失败：" + e.getMessage()); }
            }).setNegativeButton("取消", null).show();
    }

    private void showPage(int index) {
        preparePanel.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        capturePanel.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        reviewPanel.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (index != 2) reviewVideo.stopPlayback();
        if (index != 1) getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (index == 0) { stopBtn.setText("■  结束并进入整理"); stopBtn.setEnabled(true); }
    }

    private void enterReview() {
        if (session == null) return;
        showPage(2);
        reviewInfo.setText("会话 " + session.id() + "   ·   " + clock(session.durationMs()) +
            "   ·   " + session.metadata.optString("origin") + (session.finalArt().isFile() ? "   ·   已有终稿" : ""));
        reviewVideo.stopPlayback();
        reviewVideo.setVideoPath(session.rawVideo().getAbsolutePath());
        MediaController controller = new MediaController(this);
        controller.setAnchorView(reviewVideo);
        reviewVideo.setMediaController(controller);
        reviewVideo.seekTo(50);
        exportStatus.setText("每 ZIP 严格小于 400 MB；音频直接封装，原始视频不进入上传包。");
        cloudConsent.setChecked(false); // New explicit opt-in required for each session/view.
        renderEvents();
        renderSpeech();
    }

    private void requestQwenRecognition() {
        if (busy || recordingActive || session == null) return;
        if (!cloudConsent.isChecked()) {
            alert("请先确认已取得参与者所需同意，且同意将本次录音发送到 DashScope 云端。\n\n此操作不会上传视频或本机 API Key 文件。");
            return;
        }
        if (!ApiKeyVault.hasKey(this)) { alert("请回到首页，先保存 DashScope API Key"); return; }
        File existing = new File(session.dir, "analysis/qwen3_segments.json");
        String warning = existing.isFile() ? "此前已识别过，再次调用会重新计费并覆盖本地识别结果。\n\n" : "";
        new AlertDialog.Builder(this).setTitle("发送本次音频到 DashScope？")
            .setMessage(warning + "采用 Qwen3 ASR Flash Realtime 单次流式识别，返回文字与七类声音情绪标签。\n\n实际持续时间约等于音频时长；请保持 App 前台，并确认云端数据授权。")
            .setNegativeButton("取消", null)
            .setPositiveButton("确认发送", (d, which) -> analyzeCurrentAudio()).show();
    }

    private void analyzeCurrentAudio() {
        if (session == null || busy) return;
        final SessionStore.Session current = session;
        setBusy(true, "准备读取本机原始音轨（录像不会修改）…", true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        io.execute(() -> {
            File pcm = new File(current.dir, "analysis/work_pcm16.pcm");
            try {
                String key = ApiKeyVault.load(this);
                Pcm16StreamDecoder.Result decoded = new Pcm16StreamDecoder().decode(current.rawVideo(), pcm,
                    message -> runOnUiThread(() -> speechStatus.setText(message)));
                // Previous speaker mapping belongs to an older ASR run and must not be exported again.
                File oldFusion = new File(current.dir, "analysis/fused_timeline.json");
                if (oldFusion.isFile() && !oldFusion.delete())
                    throw new java.io.IOException("无法清除旧声纹时间轴；请检查本地存储");
                JSONObject result = new Qwen3RealtimeClient().recognize(pcm, new File(current.dir, "analysis"),
                    key, ApiKeyVault.region(this), ApiKeyVault.workspace(this), decoded.decodedFirstPtsMs,
                    message -> runOnUiThread(() -> speechStatus.setText(message)));
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    setBusy(false, "云端语音识别已完成，可导出含情绪标签的 GPT-6 资料包。", true);
                    speechStatus.setText("✓ Qwen3 完成，" + result.optInt("recognized_segments") + " 个语音段 · 请核听关键原话与情绪标签");
                    renderSpeech();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    setBusy(false, "语音识别失败：" + e.getMessage() + "。原始录像未丢失。", true);
                    speechStatus.setText("识别未完成：" + e.getMessage());
                    renderSpeech();
                });
            } finally {
                if (pcm.exists()) pcm.delete(); // Never export intermediate PCM or include it in backups.
            }
        });
    }

    private void renderSpeech() {
        speechList.removeAllViews();
        if (session == null) return;
        File merged = new File(session.dir, "analysis/fused_timeline.json");
        File asr = new File(session.dir, "analysis/qwen3_segments.json");
        if (!asr.isFile()) { speechStatus.setText("未识别；无网络也能录制和导出"); return; }
        try {
            JSONObject data = SessionStore.readJsonObject(asr);
            if (!"completed".equals(data.optString("status"))) {
                speechStatus.setText("上次识别未完整结束；请检查网络并重新执行");
                return;
            }
            JSONObject view = merged.isFile() ? SessionStore.readJsonObject(merged) : data;
            JSONArray words = view.getJSONArray("segments");
            speechStatus.setText("✓ 已识别 " + words.length() + " 段 · 语音情绪为算法标签，不能当作心理结论");
            for (int i = 0; i < words.length(); i++) {
                JSONObject item = words.getJSONObject(i);
                long time = item.optLong("start_ms", -1);
                String speaker = item.optString("speaker_id", "unknown");
                String emotion = item.optString("emotion", "未知");
                TextView line = new TextView(this);
                line.setText((time < 0 ? "--:--:--" : clock(time)) + " · " + speaker + " · [声音标签: " + emotion + "]\n"
                        + item.optString("transcript", "") + "\n（点按回听，身份及语义待核对）");
                line.setTextSize(13);
                line.setTextColor(ContextCompat.getColor(this, R.color.ink));
                line.setPadding(8, 12, 8, 12);
                if (time >= 0) line.setOnClickListener(v -> reviewVideo.seekTo((int)Math.min(Integer.MAX_VALUE, time)));
                speechList.addView(line);
            }
        } catch (Exception e) { speechStatus.setText("语音资料解析失败：" + e.getMessage()); }
    }

    private void renderEvents() {
        eventList.removeAllViews();
        if (session == null) return;
        try {
            JSONArray events = SessionStore.readEvents(session);
            if (events.length() == 0) {
                TextView empty = new TextView(this); empty.setText("暂无事件。可在回看播放时补记。");
                empty.setTextSize(13); empty.setPadding(0, 16, 0, 16); eventList.addView(empty);
                return;
            }
            java.util.ArrayList<JSONObject> sorted = new java.util.ArrayList<>();
            for (int i = 0; i < events.length(); i++) sorted.add(events.getJSONObject(i));
            sorted.sort((a, b) -> Long.compare(a.optLong("media_time_ms"), b.optLong("media_time_ms")));
            for (JSONObject e : sorted) {
                long t = e.optLong("media_time_ms");
                TextView item = new TextView(this);
                String note = e.optString("observer_note");
                item.setText("▸  " + clock(t) + "  " + e.optString("event_type") +
                    (note.isEmpty() ? "" : "\n     " + note));
                item.setTextSize(14); item.setTextColor(ContextCompat.getColor(this, R.color.ink));
                item.setPadding(9, 14, 9, 14); item.setMinHeight(52);
                item.setOnClickListener(v -> { reviewVideo.seekTo((int)Math.min(Integer.MAX_VALUE, t)); });
                eventList.addView(item);
            }
        } catch (Exception e) { alert("无法显示事件列表：" + e.getMessage()); }
    }

    private void setBusy(boolean isBusy, String message, boolean onReview) {
        busy = isBusy;
        recordBtn.setEnabled(!isBusy);
        findViewById(R.id.btn_system_camera).setEnabled(!isBusy);
        findViewById(R.id.btn_import).setEnabled(!isBusy);
        findViewById(R.id.btn_resume).setEnabled(!isBusy);
        spinner.setVisibility(isBusy ? View.VISIBLE : View.GONE);
        exportBtn.setEnabled(!isBusy);
        archiveBtn.setEnabled(!isBusy);
        recognizeBtn.setEnabled(!isBusy);
        findViewById(R.id.btn_import_speaker).setEnabled(!isBusy);
        exportStatus.setText(message);
    }

    private interface Task { void run() throws Exception; }
    private void work(String text, Task task, Runnable success) {
        if (busy) return;
        setBusy(true, text, true);
        io.execute(() -> {
            try {
                task.run();
                runOnUiThread(() -> { setBusy(false, "完成", true); success.run(); });
            } catch (Exception e) { runOnUiThread(() -> {
                setBusy(false, "失败：" + e.getMessage(), true);
                alert(e.getMessage());
            }); }
        });
    }

    private static String clock(long ms) {
        long sec = Math.max(0L, ms) / 1000;
        return String.format(Locale.US, "%02d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60);
    }
    private void alert(String message) {
        new AlertDialog.Builder(this).setMessage(message == null ? "未知错误" : message)
            .setPositiveButton("确定", null).show();
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (!recordingActive) io.shutdown();
    }
}
