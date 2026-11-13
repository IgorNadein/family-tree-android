package app.familygem;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Button;
import app.familygem.update.GitHubUpdateUi;
import app.familygem.databinding.AboutActivityBinding;

public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        setContent(AboutActivityBinding.inflate(getLayoutInflater()).getRoot());
        bar.setTitle(R.string.about);

        TextView version = findViewById(R.id.about_version);
        version.setText(getString(R.string.version_name, BuildConfig.VERSION_NAME));
        Button updateButton = findViewById(R.id.github_update_check);
        TextView updateStatus = findViewById(R.id.github_update_status);
        updateButton.setOnClickListener(view -> GitHubUpdateUi.check(this, updateButton, updateStatus));

        TextView webSite = findViewById(R.id.about_link);
        if (Global.settings.premium)
            webSite.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.familygem.app"))));
        else
            webSite.setVisibility(View.GONE);
    }
}
