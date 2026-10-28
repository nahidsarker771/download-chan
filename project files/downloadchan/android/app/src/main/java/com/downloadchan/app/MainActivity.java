package com.downloadchan.app;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(DownloaderPlugin.class);
        super.onCreate(savedInstanceState);
        handleShareIntent(getIntent());
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_VIEW.equals(action)) {
            String text = intent.getStringExtra(Intent.EXTRA_TEXT);
            if (text == null && intent.getData() != null) {
                text = intent.getData().toString();
            }
            if (text == null && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
                CharSequence cs = intent.getClipData().getItemAt(0).coerceToText(this);
                if (cs != null) text = cs.toString();
            }
            if (text != null && !text.trim().isEmpty()) {
                DownloaderPlugin.setPendingShare(text);
                DownloaderPlugin.emitShareToJs(text);
            }
        }
    }
}
