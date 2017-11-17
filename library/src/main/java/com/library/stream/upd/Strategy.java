package com.library.stream.upd;

import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.library.stream.IsLiveBuffer;
import com.library.util.OtherUtil;

import java.util.concurrent.ArrayBlockingQueue;

/**
 * 由于视频帧数量（一般20左右）小于音频帧数量（40左右），所以该缓存策略以视频流畅为基准
 */

public class Strategy {
    private ArrayBlockingQueue<FramesObject> videoframes = new ArrayBlockingQueue<>(OtherUtil.QueueNum);
    private ArrayBlockingQueue<FramesObject> voiceframes = new ArrayBlockingQueue<>(OtherUtil.QueueNum);
    private CachingStrategyCallback cachingStrategyCallback;

    private HandlerThread handlerVideoThread;
    private HandlerThread handlerVoiceThread;
    private Handler VideoHandler;
    private Handler VoiceHandler;

    private boolean iscode = false;//开始缓存标志

    private boolean isVideocode = false;//开始播放标志

    private int VDtime;//视频帧绝对时间
    private int VCtime;//音频帧绝对时间

    private int videomin = 20;//视频帧缓存达到播放条件

    private int videoCarltontime = 500;//视频帧缓冲时间
    private int voiceCarltontime = 100;//音频帧缓冲时间

    private final int frameControltime = 10;//帧时间控制
    private final int voiceFrameControltime = 100;//音视频帧同步时间差错范围

    private IsLiveBuffer isLiveBuffer;

    public void setCachingStrategyCallback(CachingStrategyCallback cachingStrategyCallback) {
        this.cachingStrategyCallback = cachingStrategyCallback;
    }

    public void addVideo(int timedifference, int time, byte[] video) {
        OtherUtil.addQueue(videoframes, new FramesObject(
                Math.max(20, Math.min(200, timedifference)),//视频帧时间差控制在20-180之间
                time,
                video));
    }

    public void addVoice(int timedifference, int time, byte[] voice) {
        OtherUtil.addQueue(voiceframes, new FramesObject(
                Math.max(1, Math.min(80, timedifference)),
                time,
                voice));//音频帧时间差控制在1-80之间
    }

    private Runnable videoRunnable = new Runnable() {
        @Override
        public void run() {
            if (iscode) {
                if (isVideocode && videoframes.size() > 0) {
                    FramesObject framesObject = videoframes.poll();
                    if (videoframes.size() > (videomin + 10)) {//帧缓存峰值为起始条件 +10，超过这个值则加快 frameControltime ms播放
                        VideoHandler.postDelayed(this, framesObject.getTimedifference() - frameControltime);
                    } else {
                        VideoHandler.postDelayed(this, framesObject.getTimedifference());
                    }
                    Log.d("playerInfromation_vd", "视频队列数--" + videoframes.size() + "--时间戳--" + framesObject.getTimedifference());
                    VDtime = framesObject.getTime();
                    cachingStrategyCallback.videoStrategy(framesObject.getData());
                } else {
                    isVideocode = false;//进入这个位置一定是videoframes.size() < 0，关闭开关标志
                    if (videoframes.size() > videomin) {
                        if (isLiveBuffer != null) {
                            //结束缓冲，回调给客户端
                            isLiveBuffer.isLiveBuffer(false);
                        }
                        isVideocode = true;
                        VideoHandler.post(this);
                    } else {
                        if (isLiveBuffer != null) {
                            //开始缓冲，回调给客户端
                            isLiveBuffer.isLiveBuffer(true);
                        }
                        VideoHandler.postDelayed(this, videoCarltontime);
                    }
                }
            }
        }
    };

    private Runnable voiceRunnable = new Runnable() {
        @Override
        public void run() {
            if (iscode) {
                if (isVideocode && voiceframes.size() > 0) {//这里为什么用视频标志来判断，因为视频不播放的时候不需要播放声音
                    FramesObject framesObject = voiceframes.poll();
                    VCtime = framesObject.getTime();
                    if ((VCtime - VDtime) > voiceFrameControltime) {//音频帧快了，音频要慢一点
                        VoiceHandler.postDelayed(this, framesObject.getTimedifference() + frameControltime);
                    } else if ((VDtime - VCtime) > voiceFrameControltime) {//视频帧快了，音频要快一点
                        if ((VDtime - VCtime) > (voiceFrameControltime * 3)) {
                            Log.d("playerInfromation_loss", "音频帧过慢，丢弃部分");
                            while (voiceframes.size() > 0) {
                                if ((voiceframes.poll().getTime() - VDtime) < voiceFrameControltime) {
                                    break;
                                }
                            }
                        }
                        VoiceHandler.post(this);
                    } else {
                        VoiceHandler.postDelayed(this, framesObject.getTimedifference());
                    }
                    Log.d("playerInfromation_vc", "音频队列数--" + voiceframes.size() + "--时间戳--" + framesObject.getTimedifference());
                    cachingStrategyCallback.voiceStrategy(framesObject.getData());
                } else {
                    VoiceHandler.postDelayed(this, voiceCarltontime);
                }
            }
        }
    };

    public void star() {
        videoframes.clear();
        voiceframes.clear();
        iscode = true;

        handlerVideoThread = new HandlerThread("VideoPlayer");
        handlerVoiceThread = new HandlerThread("VoicePlayer");
        handlerVideoThread.start();
        handlerVoiceThread.start();
        VideoHandler = new Handler(handlerVideoThread.getLooper());
        VoiceHandler = new Handler(handlerVoiceThread.getLooper());

        VideoHandler.post(videoRunnable);
        VoiceHandler.post(voiceRunnable);
    }

    public void stop() {
        iscode = false;
        if (handlerVideoThread != null) {
            handlerVideoThread.quitSafely();
            handlerVoiceThread.quitSafely();
        }
    }

    public void destroy() {
        stop();
    }


    public void setVideoFrameCacheMin(int videoFrameCacheMin) {
        videomin = videoFrameCacheMin;
    }

    public void setVideoCarltontime(int videoCarltontime) {
        this.videoCarltontime = videoCarltontime;
    }

    public void setVoiceCarltontime(int voiceCarltontime) {
        this.voiceCarltontime = voiceCarltontime;
    }

    /*
     缓冲接口，用于客户端判断是否正在缓冲，根据需要决定是否需要使用
     */
    public void setIsLiveBuffer(IsLiveBuffer isLiveBuffer) {
        this.isLiveBuffer = isLiveBuffer;
    }


    private class FramesObject {
        private int timedifference;
        private int time;
        private byte[] data;

        public FramesObject(int timedifference, int time, byte[] data) {
            this.time = time;
            this.data = data;
            this.timedifference = timedifference;
        }

        public int getTimedifference() {
            return timedifference;
        }

        public int getTime() {
            return time;
        }

        public byte[] getData() {
            return data;
        }
    }
}