package com.maternote.studio;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.maternote.studio.project.StudioProject;
import com.maternote.studio.project.StudioProjectStore;
import java.io.File;

public final class StudioActivity extends Activity {
    private final StudioProjectStore store = new StudioProjectStore();
    private TextView status;

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
