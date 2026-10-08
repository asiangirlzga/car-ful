package com.tvdrive.lite;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

public class MainActivity extends Activity {
    private GameView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        view = new GameView(this);
        setContentView(view);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        return view.key(keyCode, true, e.getRepeatCount()) || super.onKeyDown(keyCode, e);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent e) {
        return view.key(keyCode, false, 0) || super.onKeyUp(keyCode, e);
    }
}
