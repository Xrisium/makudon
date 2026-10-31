package io.github.xrisium.makudon;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

/** Result variants exist only in the emulator's test APK. */
public class FixturePicker extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri uri = Uri.parse("content://io.github.xrisium.makudon.debug.test.fixtures/markdown");
        Intent result = new Intent();
        result.setClipData(ClipData.newRawUri("synthetic", uri));
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (this instanceof Multiple) result.getClipData().addItem(new ClipData.Item(uri));
        setResult(this instanceof Cancel ? RESULT_CANCELED : RESULT_OK, result);
        finish();
    }
    public static class Cancel extends FixturePicker { }
    public static class Multiple extends FixturePicker { }
}
