package com.brycewg.asrkb.aidl;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * 说点啥「外部识别服务」的会话配置 Parcelable。
 *
 * 注意：本文件的包名 / 字段顺序必须与「说点啥」服务端保持一致，
 * 因为雨燕是通过 AIDL 直接调用其对外识别服务的（该服务按事务码反序列化该对象）。
 * 字段顺序即 Parcel 写入顺序，不可随意调整。
 *
 * 本文件属于对「说点啥」(Apache-2.0) 的只读移植，仅用于雨燕侧客户端。
 */
public final class SpeechConfig implements Parcelable {

    public final String vendorId;            // 供应商ID（如 "volc"、"soniox"）；为空则按说点啥应用内设置
    public final boolean streamingPreferred;  // 调用方偏好流式
    public final Boolean punctuationEnabled;  // 标点开关；null=按应用设置
    public final Boolean autoStopOnSilence;   // 静音自动判停；null=按应用设置
    public final String sessionTag;           // 调用方自定义标记

    public SpeechConfig(
            String vendorId,
            boolean streamingPreferred,
            Boolean punctuationEnabled,
            Boolean autoStopOnSilence,
            String sessionTag) {
        this.vendorId = vendorId;
        this.streamingPreferred = streamingPreferred;
        this.punctuationEnabled = punctuationEnabled;
        this.autoStopOnSilence = autoStopOnSilence;
        this.sessionTag = sessionTag;
    }

    protected SpeechConfig(Parcel p) {
        this.vendorId = p.readString();
        this.streamingPreferred = p.readInt() != 0;
        this.punctuationEnabled = readNullableBoolean(p);
        this.autoStopOnSilence = readNullableBoolean(p);
        this.sessionTag = p.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(vendorId);
        dest.writeInt(streamingPreferred ? 1 : 0);
        writeNullableBoolean(dest, punctuationEnabled);
        writeNullableBoolean(dest, autoStopOnSilence);
        dest.writeString(sessionTag);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "SpeechConfig{vendorId=" + vendorId
                + ", streaming=" + streamingPreferred
                + ", punct=" + punctuationEnabled
                + ", autoSilence=" + autoStopOnSilence
                + ", tag=" + sessionTag + "}";
    }

    public static final Creator<SpeechConfig> CREATOR = new Creator<SpeechConfig>() {
        @Override
        public SpeechConfig createFromParcel(Parcel in) {
            return new SpeechConfig(in);
        }

        @Override
        public SpeechConfig[] newArray(int size) {
            return new SpeechConfig[size];
        }
    };

    private static void writeNullableBoolean(Parcel dest, Boolean v) {
        if (v == null) {
            dest.writeInt(-1);
        } else if (v) {
            dest.writeInt(1);
        } else {
            dest.writeInt(0);
        }
    }

    private static Boolean readNullableBoolean(Parcel p) {
        int v = p.readInt();
        if (v == -1) return null;
        return v == 1;
    }
}