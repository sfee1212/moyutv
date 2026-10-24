package com.iptv.tvplayer.utils

import android.view.KeyEvent

object KeyUtil {

    fun isActionDown(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_DOWN

    fun isActionUp(event: KeyEvent): Boolean =
        event.action == KeyEvent.ACTION_UP

    /** 确认/Enter 键：DPAD_CENTER、ENTER、空格、小键盘 Enter */
    fun isEnterKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        event.keyCode == KeyEvent.KEYCODE_ENTER ||
        event.keyCode == KeyEvent.KEYCODE_SPACE ||
        event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    /** 上键：DPAD_UP、频道+键、PAGE_UP、媒体上一曲 */
    fun isUpKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
        event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP ||
        event.keyCode == KeyEvent.KEYCODE_PAGE_UP ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS

    /** 下键：DPAD_DOWN、频道-键、PAGE_DOWN、媒体下一曲 */
    fun isDownKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_PAGE_DOWN ||
        event.keyCode == KeyEvent.KEYCODE_MEDIA_NEXT

    fun isLeftKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT

    fun isRightKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT

    fun isBackKey(event: KeyEvent): Boolean =
        event.keyCode == KeyEvent.KEYCODE_BACK

    /** 数字键 0-9（主键盘 + 小键盘） */
    fun isDigitKey(event: KeyEvent): Boolean =
        (event.keyCode >= KeyEvent.KEYCODE_0 && event.keyCode <= KeyEvent.KEYCODE_9) ||
        (event.keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && event.keyCode <= KeyEvent.KEYCODE_NUMPAD_9)

    fun isMenuKey(event: KeyEvent): Boolean =
        isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MENU

    fun isMediaPlayPause(event: KeyEvent): Boolean =
        isActionUp(event) && (
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
        )

    fun isMediaStop(event: KeyEvent): Boolean =
        isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MEDIA_STOP

    fun isMediaRewind(event: KeyEvent): Boolean =
        isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND

    fun isMediaFastForward(event: KeyEvent): Boolean =
        isActionUp(event) && event.keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
}
