package com.maternote.studio;

import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.activity.ComponentActivity;
import com.maternote.studio.exporter.StudioExporter;
import com.maternote.studio.project.StudioProject;
import com.maternote.studio.project.StudioProjectStore;
import java.io.File;

public final class StudioActivity extends ComponentActivity {
    private final StudioProjectStore store = new StudioProjectStore();
    private TextView status;
    private final ActivityResultLauncher<String> exportDestination = registerForActivityResult(
        new ActivityResultContracts.CreateDocument("application/vnd.maternote.book+zip"), uri -> {
            if (uri == null) return;
            try {
                File temporary = new File(getCacheDir(), "studio-export.mnote");
                StudioExporter.exportFixture(store.read(projectFile()), temporary);
                try (java.io.InputStream input = new java.io.FileInputStream(temporary);
                     java.io.OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new IllegalStateException("Cannot open destination");
                    byte[] buffer = new byte[64 * 1024];
                    for (int count; (count = input.read(buffer)) >= 0;) output.write(buffer, 0, count);
                }
                status.setText("검증된 .mnote를 저장했습니다.\n" + uri);
            } catch (Exception error) {
                status.setText("내보내기 실패: " + error.getMessage());
            }
        });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        TextView title = new TextView(this);
        title.setText("Maternote Studio\n독립 콘텐츠 제작 프로젝트");
        title.setTextSize(26);
        root.addView(title);
        status = new TextView(this);
        status.setPadding(0, 36, 0, 36);
        root.addView(status);
        Button create = new Button(this);
        create.setText("3페이지 · 진도 2개 테스트 프로젝트 만들기");
        create.setOnClickListener(v -> createFixture());
        root.addView(create);
        Button export = new Button(this);
        export.setText("검증 후 .mnote 내보내기");
        export.setOnClickListener(v -> {
            if (!projectFile().isFile()) createFixture();
            exportDestination.launch("maternote-studio-test.mnote");
        });
        root.addView(export);
        restore();
        setContentView(root);
    }

    private File projectFile() { return new File(getFilesDir(), "current.mnproj"); }

    private void createFixture() {
        try {
            StudioProject project = StudioProject.Companion.fixture("Studio Test Book");
            store.write(projectFile(), project);
            show(project);
        } catch (Exception error) {
            status.setText("저장 실패: " + error.getMessage());
        }
    }

    private void restore() {
        try {
            if (projectFile().isFile()) show(store.read(projectFile()));
            else status.setText("저장된 .mnproj 프로젝트가 없습니다.");
        } catch (Exception error) {
            status.setText("복원 실패: " + error.getMessage());
        }
    }

    private void show(StudioProject project) {
        status.setText(project.getTitle() + "\n페이지 " + project.getPages().size()
            + "개 · 진도 " + project.getActivities().size() + "개\n" + projectFile());
    }
}
