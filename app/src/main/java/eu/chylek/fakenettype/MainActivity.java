package eu.chylek.fakenettype;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.*;
import android.util.Log;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public class MainActivity extends Activity {

    private SharedPreferences prefs;
    private Spinner spNetworkType;
    private CheckBox cbGlobal, cbFakeSpeed, cbDebug;
    private EditText etUpSpeed, etDownSpeed;
    private EditText etPackages;
    private Button btnSave;

    // 网络类型选项
    private final String[] typeNames = {"以太网 Ethernet", "WiFi", "移动数据 Mobile", "VPN", "蓝牙"};
    private final int[] typeValues = {9, 1, 0, 17, 7};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("config", MODE_WORLD_READABLE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 40, 40, 40);
        scroll.addView(layout);
        setContentView(scroll);

        // 标题
        TextView title = new TextView(this);
        title.setText("FakeNetType - 网络类型/网速伪造");
        title.setTextSize(18);
        layout.addView(title);

        // 全局开关
        cbGlobal = new CheckBox(this);
        cbGlobal.setText("全局生效（关闭则仅选填应用生效）");
        layout.addView(cbGlobal);

        // 网络类型选择
        TextView tvType = new TextView(this);
        tvType.setText("\n伪造网络类型：");
        layout.addView(tvType);

        spNetworkType = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, typeNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spNetworkType.setAdapter(adapter);
        layout.addView(spNetworkType);

        // 应用包名列表
        TextView tvPkg = new TextView(this);
        tvPkg.setText("\n目标应用包名（逗号分隔，全局关闭时生效）：");
        layout.addView(tvPkg);

        etPackages = new EditText(this);
        etPackages.setHint("例: com.tencent.mm, com.tencent.mobileqq");
        layout.addView(etPackages);

        // 虚假网速
        cbFakeSpeed = new CheckBox(this);
        cbFakeSpeed.setText("启用虚假网速（骗过测速软件）");
        layout.addView(cbFakeSpeed);

        TextView tvUp = new TextView(this);
        tvUp.setText("上传速度 (Mbps)：");
        layout.addView(tvUp);

        etUpSpeed = new EditText(this);
        etUpSpeed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etUpSpeed.setHint("50");
        layout.addView(etUpSpeed);

        TextView tvDown = new TextView(this);
        tvDown.setText("下载速度 (Mbps)：");
        layout.addView(tvDown);

        etDownSpeed = new EditText(this);
        etDownSpeed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        etDownSpeed.setHint("100");
        layout.addView(etDownSpeed);

        // 调试日志
        cbDebug = new CheckBox(this);
        cbDebug.setText("启用调试日志（logcat | grep FakeNetType）");
        layout.addView(cbDebug);

        // 保存按钮
        btnSave = new Button(this);
        btnSave.setText("保存配置并重启相关应用");
        layout.addView(btnSave);

        TextView tvTip = new TextView(this);
        tvTip.setText("\n提示：保存后请在LSPosed中勾选目标应用并重启生效");
        layout.addView(tvTip);

        loadSettings();
        btnSave.setOnClickListener(v -> saveSettings());
    }

    private void loadSettings() {
        cbGlobal.setChecked(prefs.getBoolean("global_enable", true));
        int typeIdx = 0;
        int savedType = prefs.getInt("fake_type", 9);
        for (int i = 0; i < typeValues.length; i++) {
            if (typeValues[i] == savedType) { typeIdx = i; break; }
        }
        spNetworkType.setSelection(typeIdx);

        Set<String> pkgs = prefs.getStringSet("target_packages", new HashSet<>());
        etPackages.setText(String.join(",", pkgs));

        cbFakeSpeed.setChecked(prefs.getBoolean("fake_speed_enable", false));
        etUpSpeed.setText(String.valueOf(prefs.getInt("fake_up_speed", 50)));
        etDownSpeed.setText(String.valueOf(prefs.getInt("fake_down_speed", 100)));
        cbDebug.setChecked(prefs.getBoolean("debug_log", false));
    }

    private void saveSettings() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean("global_enable", cbGlobal.isChecked());

        int typeIdx = spNetworkType.getSelectedItemPosition();
        editor.putInt("fake_type", typeValues[typeIdx]);

        String[] pkgs = etPackages.getText().toString().split(",");
        Set<String> pkgSet = new HashSet<>(Arrays.asList(pkgs));
        editor.putStringSet("target_packages", pkgSet);

        editor.putBoolean("fake_speed_enable", cbFakeSpeed.isChecked());
        try {
            editor.putInt("fake_up_speed", Integer.parseInt(etUpSpeed.getText().toString()));
            editor.putInt("fake_down_speed", Integer.parseInt(etDownSpeed.getText().toString()));
        } catch (Exception e) {
            Toast.makeText(this, "速度请输入数字", Toast.LENGTH_SHORT).show();
            return;
        }
        editor.putBoolean("debug_log", cbDebug.isChecked());
        editor.apply();

        Toast.makeText(this, "配置已保存，请在LSPosed中勾选应用后重启", Toast.LENGTH_LONG).show();
        finish();
    }
}
