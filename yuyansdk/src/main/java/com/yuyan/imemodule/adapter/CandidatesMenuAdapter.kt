package com.yuyan.imemodule.adapter

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.callback.OnRecyclerItemClickListener
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.data.flower.FlowerTypefaceMode
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.entity.SkbFunItem
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.prefs.behavior.SymbolMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import com.yuyan.imemodule.voice.VoiceController
import com.yuyan.imemodule.voice.VoiceUiState
import splitties.views.padding

/**
 * 候选词界面适配器
 */
class CandidatesMenuAdapter(context: Context?) : RecyclerView.Adapter<CandidatesMenuAdapter.SymbolHolder>() {
    private val inflater: LayoutInflater = LayoutInflater.from(context)
    private var mOnItemClickListener: OnRecyclerItemClickListener? = null
    private var mVoiceButtonListener: OnVoiceButtonListener? = null
    private var itemHeight: Int = (instance.heightForCandidatesArea * 0.8f).toInt()
    private var mMenuPadding: Int = (instance.heightForCandidatesArea * 0.05f).toInt()
    var items: List<SkbFunItem> = emptyList()
        set(value) {
            val diffResult = DiffUtil.calculateDiff(MyDiffCallback(field, value))
            field = value
            diffResult.dispatchUpdatesTo(this)
        }

    /** 录音状态变化时刷新图标（红色提示正在录音） */
    init {
        VoiceUiState.addListener {
            notifyDataSetChanged()
        }
    }

    fun setOnItemClickLitener(mOnItemClickLitener: OnRecyclerItemClickListener?) {
        mOnItemClickListener = mOnItemClickLitener
    }

    /** 语音按钮按住/松手回调（HOLD 模式用） */
    fun setOnVoiceButtonListener(listener: OnVoiceButtonListener?) {
        mVoiceButtonListener = listener
    }

    interface OnVoiceButtonListener {
        fun onVoiceDown(skbMenuMode: SkbMenuMode)
        fun onVoiceUp()
    }

    /** 该按钮是否为长按录音模式 */
    private fun isHoldVoice(mode: SkbMenuMode): Boolean {
        val ctrl = VoiceController.instance ?: return false
        val b = ctrl.buttonOf(mode) ?: return false
        return ctrl.modeFor(b) == VoiceController.InteractMode.HOLD
    }

    private fun isVoiceMode(mode: SkbMenuMode): Boolean =
        mode == SkbMenuMode.Voice || mode == SkbMenuMode.VoiceB

    inner class SymbolHolder(view: View) : RecyclerView.ViewHolder(view) {
        var entranceIconImageView: ImageView? = null
        init {
            entranceIconImageView = itemView.findViewById(R.id.candidates_menu_item)
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SymbolHolder {
        return SymbolHolder(inflater.inflate(R.layout.sdk_item_recyclerview_candidates_menu, parent, false))
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: SymbolHolder, position: Int) {
        val item = items[position]
        holder.entranceIconImageView?.padding = mMenuPadding
        val layoutParams = holder.entranceIconImageView?.layoutParams
        layoutParams?.width = itemHeight
        layoutParams?.height = itemHeight
        holder.entranceIconImageView?.setImageResource(item.funImgResource)
        val baseColor = if (isSettingsMenuSelect(item)) activeTheme.accentKeyBackgroundColor else activeTheme.keyTextColor
        val color = if (isVoiceMode(item.skbMenuMode) && VoiceUiState.isRecording) Color.parseColor("#E53935") else baseColor
        holder.entranceIconImageView?.getDrawable()?.setTint(color)
        if(ThemeManager.prefs.keyBorder.getValue()) {
            val bg = GradientDrawable()
            bg.setColor(activeTheme.keyBackgroundColor)
            bg.shape = GradientDrawable.OVAL
            holder.entranceIconImageView?.background = bg
        }
        val mode = item.skbMenuMode
        if (isVoiceMode(mode) && isHoldVoice(mode)) {
            // HOLD 模式：按住开始、松手结束
            holder.itemView.setOnClickListener(null)
            holder.itemView.setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        mVoiceButtonListener?.onVoiceDown(mode)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mVoiceButtonListener?.onVoiceUp()
                        true
                    }
                    else -> true
                }
            }
        } else if (mOnItemClickListener != null) {
            holder.itemView.setOnTouchListener(null)
            holder.itemView.setOnClickListener { v: View? ->
                mOnItemClickListener!!.onItemClick(this, v, position)
            }
        }
    }

    fun getMenuMode(position: Int): SkbMenuMode? = if(items.size > position)items[position].skbMenuMode else null

    private fun isSettingsMenuSelect(data: SkbFunItem): Boolean {
        val rimeValue = AppPrefs.getInstance().internal.pinyinModeRime.getValue()
        val result: Boolean = when (data.skbMenuMode) {
            // Setting Menu
            SkbMenuMode.DarkTheme -> activeTheme.isDark
            SkbMenuMode.NumberRow -> AppPrefs.getInstance().keyboardSetting.abcNumberLine.getValue()
            SkbMenuMode.JianFan -> AppPrefs.getInstance().input.chineseFanTi.getValue()
            SkbMenuMode.LockEnglish -> AppPrefs.getInstance().keyboardSetting.keyboardLockEnglish.getValue()
            SkbMenuMode.SymbolShow -> ThemeManager.prefs.keyboardSymbol.getValue()
            SkbMenuMode.Mnemonic ->  AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
            SkbMenuMode.EmojiInput -> AppPrefs.getInstance().input.emojiInput.getValue()
            SkbMenuMode.OneHanded -> AppPrefs.getInstance().keyboardSetting.oneHandedModSwitch.getValue()
            SkbMenuMode.FlowerTypeface -> CustomConstant.flowerTypeface != FlowerTypefaceMode.Disabled
            SkbMenuMode.FloatKeyboard -> EnvironmentSingleton.instance.keyboardModeFloat
            SkbMenuMode.ClipBoard -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.getMenuMode() == SkbMenuMode.ClipBoard
            SkbMenuMode.Phrases -> (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.getMenuMode() == SkbMenuMode.Phrases
            SkbMenuMode.Emojicon -> (KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == SymbolMode.Emojicon
            SkbMenuMode.Emoticon -> (KeyboardManager.instance.currentContainer as? SymbolContainer)?.getMenuMode() == SymbolMode.Emoticon
            // Keyboard Menu
            SkbMenuMode.PinyinT9 -> rimeValue == CustomConstant.SCHEMA_ZH_T9
            SkbMenuMode.Pinyin26Jian -> rimeValue == CustomConstant.SCHEMA_ZH_QWERTY
            SkbMenuMode.PinyinHandWriting -> rimeValue == CustomConstant.SCHEMA_ZH_HANDWRITING
            SkbMenuMode.PinyinLx17 -> rimeValue == CustomConstant.SCHEMA_ZH_DOUBLE_LX17
            SkbMenuMode.Pinyin26Double -> rimeValue.startsWith(CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY) && rimeValue != CustomConstant.SCHEMA_ZH_DOUBLE_LX17
            SkbMenuMode.PinyinStroke -> rimeValue == CustomConstant.SCHEMA_ZH_STROKE
            SkbMenuMode.LockClipBoard -> CustomConstant.lockClipBoardEnable
            SkbMenuMode.TextEdit -> InputModeSwitcher.isTextEditSkb
            else -> false
        }
        return result
    }

    class MyDiffCallback(private val oldList: List<SkbFunItem>, private val newList: List<SkbFunItem>) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size
        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean = true
        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].funName == newList[newItemPosition].funName
        }
    }

    fun notifyChanged() {
        itemHeight = (instance.heightForCandidatesArea * 0.8f).toInt()
        mMenuPadding = (instance.heightForCandidatesArea * 0.05f).toInt()
        notifyDataSetChanged()
    }
}