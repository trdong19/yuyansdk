package com.yuyan.imemodule.service

import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.yuyan.imemodule.candidate.CandidateView
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.OnThemeChangeListener
import com.yuyan.imemodule.data.theme.ThemeManager.addOnChangedListener
import com.yuyan.imemodule.data.theme.ThemeManager.onSystemDarkModeChange
import com.yuyan.imemodule.data.theme.ThemeManager.removeOnChangedListener
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.utils.isDarkMode
import com.yuyan.imemodule.view.preference.ManagedPreference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import splitties.bitflags.hasFlag

/**
 * Main class of the Pinyin input method. 输入法服务
 */
class ImeService : InputMethodService() {
    private var isHardwareKeyboard = false
    private var ctrlHandled = false
    private var shiftPressed = false  // Shift 是否单独按下（用于切换中英文）
    private var shiftConsumed = false  // Shift 是否被其他按键消费（Shift+字母时不切换）
    private var isWindowShown = false // 键盘窗口是否已显示
    var isTerminalEditor = false // 当前编辑器是否为终端类应用（inputType == TYPE_NULL）
    private lateinit var mInputView: InputView
    private lateinit var mCandidateView: CandidateView
    private val onThemeChangeListener = OnThemeChangeListener { _: Theme? -> if (::mInputView.isInitialized) mInputView.updateTheme() }
    private val clipboardUpdateContent = getInstance().internal.clipboardUpdateContent
    private val clipboardUpdateContentListener = ManagedPreference.OnChangeListener<String> { _, value ->
        if(::mInputView.isInitialized && mInputView.isShown && getInstance().clipboard.clipboardSuggestion.getValue()){
            if(value.isNotBlank()) {
                if(KeyboardManager.instance.currentContainer is ClipBoardContainer
                    && (KeyboardManager.instance.currentContainer as ClipBoardContainer).getMenuMode() == SkbMenuMode.ClipBoard ){
                    (KeyboardManager.instance.currentContainer as ClipBoardContainer).showClipBoardView(SkbMenuMode.ClipBoard)
                } else {
                    mInputView.showSymbols(arrayOf(value))
                }
            }
        }
    }
    override fun onCreate() {
        super.onCreate()
        addOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.registerOnChangeListener(clipboardUpdateContentListener)
    }

    override fun onCreateInputView(): View {
        mInputView = InputView(baseContext, this)
        return mInputView
    }

    override fun onCreateCandidatesView(): View {
        mCandidateView = CandidateView(baseContext, this)
        return mCandidateView
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        YuyanEmojiCompat.setEditorInfo(editorInfo)
        isTerminalEditor = editorInfo != null && (editorInfo.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_NULL
        isHardwareKeyboard =  resources.configuration.keyboard != Configuration.KEYBOARD_NOKEYS
        if(isHardwareKeyboard) {
            setCandidatesViewShown(true)
            currentInputConnection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        }
        if (::mCandidateView.isInitialized)mCandidateView.onStartInput(editorInfo, restarting)
        super.onStartInput(editorInfo, restarting)
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (::mInputView.isInitialized)mInputView.onStartInputView(editorInfo, restarting)
        super.onStartInputView(editorInfo, restarting)
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOnChangedListener(onThemeChangeListener)
        clipboardUpdateContent.unregisterOnChangeListener(clipboardUpdateContentListener)
    }

    /**
     * 横竖屏切换
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isHardwareKeyboard = (newConfig.keyboard != Configuration.KEYBOARD_NOKEYS)
        CoroutineScope(Dispatchers.Main).launch {
            delay(200) //延时，解决获取屏幕尺寸不准确。
            EnvironmentSingleton.instance.initData(baseContext)
            if(isHardwareKeyboard){
                setCandidatesViewShown(true)
                currentInputConnection.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
                mCandidateView.initView()
            } else if (::mInputView.isInitialized) {
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                KeyboardManager.instance.clearKeyboard()
                KeyboardManager.instance.switchKeyboard()
            }
        }
        onSystemDarkModeChange(newConfig.isDarkMode())
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        ctrlHandled = false
        // Shift 键：标记按下，等 keyUp 时判断是否单独按下
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            shiftPressed = true
            shiftConsumed = false
            return false  // 不消费，让系统处理
        }
        // 如果 Shift 正在按住，标记为已消费（Shift+其他键组合）
        if (shiftPressed) shiftConsumed = true
        // Ctrl+字母 组合键处理
        if (event.isCtrlPressed && !event.isShiftPressed && !event.isMetaPressed) {
            when (keyCode) {
                KeyEvent.KEYCODE_C -> { currentInputConnection.performContextMenuAction(android.R.id.copy); ctrlHandled = true; return true }
                KeyEvent.KEYCODE_V -> { currentInputConnection.performContextMenuAction(android.R.id.paste); ctrlHandled = true; return true }
                KeyEvent.KEYCODE_X -> { currentInputConnection.performContextMenuAction(android.R.id.cut); ctrlHandled = true; return true }
                KeyEvent.KEYCODE_A -> { currentInputConnection.performContextMenuAction(android.R.id.selectAll); ctrlHandled = true; return true }
                KeyEvent.KEYCODE_SPACE -> {
                    InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
                    ctrlHandled = true
                    return true
                }
            }
            // 其他 Ctrl+字母 组合键交由系统处理
            return super.onKeyDown(keyCode, event)
        }
        // 长按物理按键或 Shift&Meta 的组合按键时，交由系统处理
        if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) return super.onKeyDown(keyCode, event)
        // 统一走 CandidateView 处理（onKeyDown 的按键都来自外部，非软键盘触摸）
        val handled = mCandidateView.processKeyDown(keyCode, event)
        // 有 unicodeChar 的未处理按键（标点等）：不走 super，避免系统自动 commitText 导致重复
        // 无 unicodeChar 的系统按键（BACK/HOME等）：走 super 让系统处理
        return if (handled) true
        else if (event.unicodeChar != 0) true
        else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Shift 单独松开：切换中英文（Shift+其他键时不切换）
        if (keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (shiftPressed && !shiftConsumed) {
                InputModeSwitcher.switchModeForUserKey(InputModeSwitcher.USER_KEYCODE_LANG)
            }
            shiftPressed = false
            shiftConsumed = false
            return true
        }
        // Ctrl 组合键: 已处理的返回 true 阻止系统再处理（防止输出字母）
        if (event.isCtrlPressed) {
            return if (ctrlHandled) { ctrlHandled = false; true }
            else super.onKeyUp(keyCode, event)
        }
        if (0 != event.repeatCount || event.isShiftPressed || event.isMetaPressed) return super.onKeyUp(keyCode, event)
        // 走 processKeyUp 执行真正的按键处理逻辑
        val handled = mCandidateView.processKeyUp(event)
        if (handled) return true
        // 未处理的按键：物理键盘有 unicodeChar 的直接 commitText（软键盘已由 InputView 处理，跳过避免重复输入）
        if (event.unicodeChar != 0 && event.flags and KeyEvent.FLAG_SOFT_KEYBOARD == 0) {
            val text = event.unicodeChar.toChar().toString()
            currentInputConnection?.commitText(text, 1)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val layoutParams = view.layoutParams
        if (layoutParams != null && layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
            view.setLayoutParams(layoutParams)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false //修复横屏之后输入框遮挡问题


    override fun onComputeInsets(outInsets: Insets) {
        val (x, y) =  if (::mInputView.isInitialized) intArrayOf(0, 0).also {if(mInputView.isAddPhrases) mInputView.mAddPhrasesLayout.getLocationInWindow(it) else mInputView.mSkbRoot.getLocationInWindow(it) }
        else if (::mCandidateView.isInitialized) intArrayOf(0, 0).also {mCandidateView.mSkbRoot.getLocationInWindow(it) }
        else intArrayOf(0, 0)
        outInsets.apply {
            if(EnvironmentSingleton.instance.keyboardModeFloat) {
                contentTopInsets = EnvironmentSingleton.instance.mScreenHeight
                visibleTopInsets = EnvironmentSingleton.instance.mScreenHeight
                touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                touchableRegion.set(x, y, x + mInputView.mSkbRoot.width, y + mInputView.mSkbRoot.height)
            } else {
                contentTopInsets = y
                touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
                touchableRegion.setEmpty()
                visibleTopInsets = y
            }
        }
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        if (::mInputView.isInitialized) mInputView.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesEnd)
    }

    private val cursorAnchorPosition = FloatArray(2)
    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        if (!isHardwareKeyboard || cursorAnchorInfo == null) return
        cursorAnchorPosition[0] = cursorAnchorInfo.insertionMarkerHorizontal
        cursorAnchorPosition[1] = cursorAnchorInfo.insertionMarkerBottom
        val matrix = cursorAnchorInfo.getMatrix()
        if (matrix != null) {
            matrix.mapPoints(cursorAnchorPosition)
        }
        mCandidateView.updatePosition(cursorAnchorPosition)
    }

    override fun onWindowShown() {
        if(isWindowShown) return
        isWindowShown = true
        if (::mInputView.isInitialized) mInputView.onWindowShown()
        super.onWindowShown()
    }

    override fun onWindowHidden() {
        isWindowShown = false
        if(::mInputView.isInitialized) mInputView.onWindowHidden()
        super.onWindowHidden()
    }

    /**
     * 模拟Enter按键点击
     */
    fun sendEnterKeyEvent() {
        val inputConnection = getCurrentInputConnection()
        YuyanEmojiCompat.mEditorInfo?.run {
            if (inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL || imeOptions.hasFlag(EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            } else if (!actionLabel.isNullOrEmpty() && actionId != EditorInfo.IME_ACTION_UNSPECIFIED) {
                inputConnection.performEditorAction(actionId)
            } else when (val action = imeOptions and EditorInfo.IME_MASK_ACTION) {
                EditorInfo.IME_ACTION_UNSPECIFIED, EditorInfo.IME_ACTION_NONE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                else -> inputConnection.performEditorAction(action)
            }
        }
    }

    fun sendCombinationKeyEvents(keyEventCode: Int, alt: Boolean = false, ctrl: Boolean = false, shift: Boolean = false) {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        val eventTime = SystemClock.uptimeMillis()
        if (alt) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        if (ctrl) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (shift) sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (shift) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        if (ctrl) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        if (alt) sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
    }

    fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection?.sendKeyEvent(
            KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int = 0) {
        currentInputConnection.sendKeyEvent(
            KeyEvent(eventTime, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyEventCode, 0, metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD, keyEventCode, KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE)
        )
    }

    /**
     * 向输入框提交预选词
     */
    fun setComposingText(text: CharSequence) {
        currentInputConnection.setComposingText(text, 1)
    }


    /**
     * 结束提交预选词
     */
    fun finishComposingText() {
        currentInputConnection.finishComposingText()
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), 1)
    }

    /**
     * 发送字符串给编辑框
     */
    fun commitText(text: String, newCursorPosition: Int) {
        currentInputConnection.commitText(StringUtils.converted2FlowerTypeface(text), newCursorPosition)
    }

    fun getTextBeforeCursor(length:Int) : String {
        return currentInputConnection.getTextBeforeCursor(length, 0).toString()
    }

    fun commitTextEditMenu(id:Int) {
        currentInputConnection.performContextMenuAction(id)
    }

    fun performEditorAction(editorAction:Int) {
        currentInputConnection.performEditorAction(editorAction)
    }

    fun deleteSurroundingText(length:Int) {
        currentInputConnection.deleteSurroundingText(length, 0)
    }

    fun setSelection(start: Int, end: Int) {
        currentInputConnection.setSelection(start, end)
    }
}
