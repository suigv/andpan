package com.andpan.deck;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import android.os.Process;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MicYouClient {
    public interface Listener {
        void onAudioConnectionChanged();
    }

    private static final byte[] HANDSHAKE_CLIENT = "MicYouCheck1".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HANDSHAKE_SERVER = "MicYouCheck2".getBytes(StandardCharsets.US_ASCII);
    private static final int PACKET_MAGIC = 0x4D696359;
    private static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int PCM_16BIT = 2;

    private final Listener listener;
    private final Object stateLock = new Object();
    private final Object writeLock = new Object();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final Random random = new Random();

    private String targetHost = "";
    private int targetPort = 0;
    private boolean wantConnected = false;
    private boolean shutdown = false;
    private boolean connecting = false;
    private volatile boolean connected = false;
    private volatile boolean streaming = false;
    private volatile String lastError = "";
    private Socket socket;
    private OutputStream output;
    private long sessionId;
    private int sequenceNumber;
    private volatile long lastSentAt;
    private volatile long lastPingAt;
    private volatile long lastAudioSentAt;
    private Thread captureThread;
    private int streamGeneration;

    public MicYouClient(Listener listener) {
        this.listener = listener;
        worker.scheduleWithFixedDelay(this::tick, 1000, 1500, TimeUnit.MILLISECONDS);
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        synchronized (stateLock) {
            return connecting && !connected;
        }
    }

    public String getLastError() {
        return lastError;
    }

    public void ensureConnected(String host, int port) {
        if (host == null || host.isEmpty() || port <= 0 || port > 65535) return;
        boolean changed = false;
        synchronized (stateLock) {
            if (shutdown) return;
            if (!host.equals(targetHost) || port != targetPort || !wantConnected) {
                changed = !host.equals(targetHost) || port != targetPort;
                targetHost = host;
                targetPort = port;
                wantConnected = true;
            }
        }
        if (changed) {
            closeSocket("切换目标");
        }
        if (!connected) {
            worker.execute(this::connectIfNeeded);
        }
    }

    public void reconnectNow() {
        worker.execute(this::connectIfNeeded);
    }

    public void disconnect() {
        stopStreaming();
        synchronized (stateLock) {
            wantConnected = false;
        }
        closeSocket("");
    }

    public void shutdown() {
        synchronized (stateLock) {
            shutdown = true;
            wantConnected = false;
        }
        stopStreaming();
        closeSocket("");
        worker.shutdownNow();
    }

    public void startStreaming() throws Exception {
        if (!connected) {
            connectBlocking();
        }
        if (!connected) {
            throw new IOException(lastError.isEmpty() ? "语音通道未连接" : lastError);
        }
        final int generation;
        synchronized (stateLock) {
            stopCaptureLocked();
            streaming = true;
            lastAudioSentAt = System.currentTimeMillis();
            generation = ++streamGeneration;
        }
        try {
            sendMute(false);
        } catch (IOException e) {
            synchronized (stateLock) {
                if (generation == streamGeneration) streaming = false;
            }
            closeSocket("语音通道发送失败");
            throw e;
        }
        Thread thread = new Thread(() -> runCaptureLoop(generation), "andpan-audio-tx");
        synchronized (stateLock) {
            if (!streaming || generation != streamGeneration) {
                return;
            }
            captureThread = thread;
        }
        thread.start();
    }

    public void stopStreaming() {
        Thread old;
        synchronized (stateLock) {
            streaming = false;
            streamGeneration++;
            old = captureThread;
            captureThread = null;
        }
        if (old != null && old != Thread.currentThread()) {
            try {
                old.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (connected) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                worker.execute(() -> {
                    try { sendMute(true); } catch (IOException ignored) {}
                });
            } else {
                try { sendMute(true); } catch (IOException ignored) {}
            }
        }
    }

    private void stopCaptureLocked() {
        streaming = false;
        streamGeneration++;
        captureThread = null;
    }

    private void tick() {
        try {
            boolean shouldConnect;
            long now = System.currentTimeMillis();
            synchronized (stateLock) {
                shouldConnect = !shutdown && wantConnected && !connected;
            }
            if (shouldConnect) {
                connectIfNeeded();
            } else if (connected && (now - lastSentAt >= 1200 || now - lastPingAt >= 2500)) {
                lastPingAt = now;
                sendPing(now);
            }
        } catch (Exception e) {
            closeSocket("语音连接中断");
        }
    }

    private void connectIfNeeded() {
        try {
            connectBlocking();
        } catch (Exception ignored) {}
    }

    private void connectBlocking() throws IOException {
        String host;
        int port;
        synchronized (stateLock) {
            if (shutdown || !wantConnected || connected || connecting) return;
            host = targetHost;
            port = targetPort;
            if (host.isEmpty() || port <= 0) return;
            connecting = true;
        }
        notifyListener();
        Socket candidate = null;
        try {
            candidate = new Socket();
            candidate.setTcpNoDelay(true);
            candidate.setKeepAlive(true);
            candidate.connect(new InetSocketAddress(host, port), 3000);
            candidate.setSoTimeout(0);
            OutputStream out = candidate.getOutputStream();
            InputStream in = candidate.getInputStream();
            out.write(HANDSHAKE_CLIENT);
            out.flush();
            byte[] serverHello = new byte[HANDSHAKE_SERVER.length];
            readFully(in, serverHello);
            if (!Arrays.equals(serverHello, HANDSHAKE_SERVER)) {
                throw new IOException("语音服务握手失败");
            }
            long sid = Math.abs(random.nextLong());
            if (sid == 0) sid = 1;
            byte[] connectPayload = encodeConnect(sid);
            writeRawFrame(out, connectPayload);
            byte[] mutePayload = encodeMute(true);
            writeRawFrame(out, mutePayload);

            synchronized (stateLock) {
                if (shutdown || !wantConnected || !host.equals(targetHost) || port != targetPort) {
                    candidate.close();
                    return;
                }
                this.socket = candidate;
                this.output = out;
                this.sessionId = sid;
                this.sequenceNumber = 0;
                this.connected = true;
                this.lastError = "";
                this.lastSentAt = System.currentTimeMillis();
                this.lastPingAt = System.currentTimeMillis();
            }
            final Socket activeSocket = candidate;
            Thread reader = new Thread(() -> runReader(activeSocket, in), "andpan-audio-rx");
            reader.start();
            notifyListener();
        } catch (IOException e) {
            if (candidate != null) {
                try { candidate.close(); } catch (IOException ignored) {}
            }
            synchronized (stateLock) {
                lastError = e.getMessage() == null ? "无法连接语音服务" : e.getMessage();
            }
            notifyListener();
            throw e;
        } finally {
            synchronized (stateLock) {
                connecting = false;
            }
            notifyListener();
        }
    }

    private void runReader(Socket activeSocket, InputStream in) {
        byte[] header = new byte[8];
        try {
            while (true) {
                readFully(in, header);
                int magic = ((header[0] & 0xFF) << 24) | ((header[1] & 0xFF) << 16)
                        | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
                int length = ((header[4] & 0xFF) << 24) | ((header[5] & 0xFF) << 16)
                        | ((header[6] & 0xFF) << 8) | (header[7] & 0xFF);
                if (magic != PACKET_MAGIC || length < 0 || length > MAX_FRAME_BYTES) {
                    throw new IOException("语音服务协议异常");
                }
                byte[] payload = new byte[length];
                readFully(in, payload);
                handleServerPayload(payload);
            }
        } catch (IOException e) {
            synchronized (stateLock) {
                if (socket == activeSocket) {
                    closeSocketLocked("语音连接已断开");
                }
            }
            notifyListener();
        }
    }

    private void handleServerPayload(byte[] payload) throws IOException {
        int pos = 0;
        while (pos < payload.length) {
            long tag = readVarint(payload, pos);
            pos = nextVarintPos(payload, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 2) {
                int len = (int) readVarint(payload, pos);
                pos = nextVarintPos(payload, pos);
                if (len < 0 || pos + len > payload.length) return;
                if (field == 5) {
                    long ts = decodeTimestamp(payload, pos, pos + len);
                    sendPong(ts);
                }
                pos += len;
            } else if (wire == 0) {
                pos = nextVarintPos(payload, pos);
            } else if (wire == 1) {
                pos += 8;
            } else if (wire == 5) {
                pos += 4;
            } else {
                return;
            }
        }
    }

    private static long decodeTimestamp(byte[] data, int start, int end) {
        int pos = start;
        while (pos < end) {
            long tag = readVarint(data, pos);
            pos = nextVarintPos(data, pos);
            int field = (int) (tag >>> 3);
            int wire = (int) (tag & 7);
            if (wire == 0) {
                long value = readVarint(data, pos);
                pos = nextVarintPos(data, pos);
                if (field == 1) return value;
            } else if (wire == 2) {
                int len = (int) readVarint(data, pos);
                pos = nextVarintPos(data, pos) + Math.max(0, len);
            } else if (wire == 1) {
                pos += 8;
            } else if (wire == 5) {
                pos += 4;
            } else {
                break;
            }
        }
        return System.currentTimeMillis();
    }

    private void runCaptureLoop(int generation) {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        } catch (Throwable ignored) {}
        int attempts = 0;
        while (streaming && generation == streamGeneration) {
            AudioRecordHolder holder = null;
            try {
                holder = openAudioRecord();
                holder.record.startRecording();
                if (holder.record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                    throw new IOException("手机麦克风未处于录制状态");
                }
                attempts = 0;
                byte[] pcm = new byte[holder.frameBytes];
                long streamStartedAt = System.currentTimeMillis();
                long silentSamplesCount = 0;
                long lastEnergyAt = System.currentTimeMillis();
                while (streaming && generation == streamGeneration && connected) {
                    int offset = 0;
                    while (offset < pcm.length && streaming && generation == streamGeneration) {
                        int read = holder.record.read(pcm, offset, pcm.length - offset);
                        if (read < 0) {
                            throw new IOException("读取麦克风数据失败: " + read);
                        }
                        if (read == 0) {
                            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
                        }
                        offset += read;
                    }
                    if (!streaming || generation != streamGeneration || !connected) break;

                    // 计算振幅，检测是否出现声卡低功耗/死机导致的绝对全零或极度静默
                    long sumSquares = 0;
                    for (int i = 0; i < pcm.length - 1; i += 2) {
                        short sample = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8));
                        sumSquares += (long) sample * sample;
                    }
                    double rms = Math.sqrt((double) sumSquares / (pcm.length / 2));
                    long now = System.currentTimeMillis();
                    if (rms > 80.0) {
                        lastEnergyAt = now;
                    }

                    long sid;
                    int seq;
                    synchronized (stateLock) {
                        sid = sessionId;
                        seq = sequenceNumber++;
                        if (sequenceNumber < 0) sequenceNumber = 0;
                    }
                    byte[] payload = encodeAudio(seq, pcm, holder.sampleRate, sid, System.currentTimeMillis());
                    sendFrame(payload);
                    lastAudioSentAt = System.currentTimeMillis();

                    // 闲置自愈机制：
                    // 1. 如果连续 90 秒没有任何哪怕微小的声音能量（极大概率声卡时钟被系统休眠截断）；
                    // 2. 或持续收音达到 120 秒；
                    // 在后台无感重新初始化 AudioRecord，强行唤醒底层硬件 ADC 增益状态，彻底免去手动重开。
                    if (now - lastEnergyAt > 90000 || now - streamStartedAt > 120000) {
                        android.util.Log.i("AndPan", "执行声卡防休眠热复位");
                        break;
                    }
                }
                if (!streaming || generation != streamGeneration || !connected) break;
            } catch (Exception e) {
                attempts++;
                android.util.Log.w("AndPan", "采音异常，正在重试 (" + attempts + "): " + e.getMessage());
                if (holder != null) holder.release();
                if (!streaming || generation != streamGeneration || attempts >= 3) {
                    synchronized (stateLock) {
                        if (generation == streamGeneration) {
                            lastError = e.getMessage() == null ? "手机采音中断" : e.getMessage();
                            streaming = false;
                        }
                    }
                    notifyListener();
                    break;
                }
                try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            } finally {
                if (holder != null) holder.release();
            }
        }
    }

    private static final class AudioRecordHolder {
        final AudioRecord record;
        final int sampleRate;
        final int frameBytes;
        AutomaticGainControl agc;
        NoiseSuppressor ns;

        AudioRecordHolder(AudioRecord record, int sampleRate, int frameBytes) {
            this.record = record;
            this.sampleRate = sampleRate;
            this.frameBytes = frameBytes;
            try {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(record.getAudioSessionId());
                    if (agc != null) agc.setEnabled(false);
                }
            } catch (Throwable ignored) {}
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(record.getAudioSessionId());
                    if (ns != null) ns.setEnabled(false);
                }
            } catch (Throwable ignored) {}
        }

        void release() {
            try { if (agc != null) agc.release(); } catch (Throwable ignored) {}
            try { if (ns != null) ns.release(); } catch (Throwable ignored) {}
            agc = null;
            ns = null;
            try {
                if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop();
                }
            } catch (IllegalStateException ignored) {}
            try { record.release(); } catch (Exception ignored) {}
        }
    }

    private AudioRecordHolder openAudioRecord() throws IOException {
        int[] rates = new int[]{48000, 16000};
        int[] sources = new int[]{MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC};
        for (int source : sources) {
            for (int rate : rates) {
                int minSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minSize <= 0) continue;
                int frameBytes = (rate / 50) * 2;
                int bufferBytes = Math.max(minSize, frameBytes * 4);
                AudioRecord record = null;
                try {
                    record = new AudioRecord(
                            source,
                            rate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferBytes
                    );
                    if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                        return new AudioRecordHolder(record, rate, frameBytes);
                    }
                    record.release();
                } catch (Exception e) {
                    if (record != null) {
                        try { record.release(); } catch (Exception ignored) {}
                    }
                }
            }
        }
        throw new IOException("无法初始化手机麦克风");
    }

    private void sendPing(long timestamp) throws IOException {
        ByteArrayOutputStream inner = new ByteArrayOutputStream(12);
        writeTag(inner, 1, 0);
        writeVarint(inner, timestamp);
        ByteArrayOutputStream wrapper = new ByteArrayOutputStream(16);
        writeBytesField(wrapper, 5, inner.toByteArray());
        sendFrame(wrapper.toByteArray());
    }

    private void sendPong(long timestamp) throws IOException {
        ByteArrayOutputStream inner = new ByteArrayOutputStream(12);
        writeTag(inner, 1, 0);
        writeVarint(inner, timestamp);
        ByteArrayOutputStream wrapper = new ByteArrayOutputStream(16);
        writeBytesField(wrapper, 6, inner.toByteArray());
        sendFrame(wrapper.toByteArray());
    }

    private void sendMute(boolean muted) throws IOException {
        sendFrame(encodeMute(muted));
    }

    private static byte[] encodeConnect(long sessionId) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream(24);
        writeTag(inner, 1, 0);
        writeVarint(inner, sessionId);
        writeStringField(inner, 2, "android");
        ByteArrayOutputStream wrapper = new ByteArrayOutputStream(32);
        writeBytesField(wrapper, 2, inner.toByteArray());
        return wrapper.toByteArray();
    }

    private static byte[] encodeMute(boolean muted) {
        ByteArrayOutputStream inner = new ByteArrayOutputStream(4);
        writeTag(inner, 1, 0);
        writeVarint(inner, muted ? 1 : 0);
        ByteArrayOutputStream wrapper = new ByteArrayOutputStream(8);
        writeBytesField(wrapper, 3, inner.toByteArray());
        return wrapper.toByteArray();
    }

    private static byte[] encodeAudio(int seq, byte[] pcm, int sampleRate, long sessionId, long timestamp) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream(pcm.length + 24);
        writeBytesField(audio, 1, pcm);
        writeTag(audio, 2, 0);
        writeVarint(audio, sampleRate);
        writeTag(audio, 3, 0);
        writeVarint(audio, 1);
        writeTag(audio, 4, 0);
        writeVarint(audio, PCM_16BIT);

        byte[] audioBytes = audio.toByteArray();
        ByteArrayOutputStream ordered = new ByteArrayOutputStream(audioBytes.length + 32);
        writeTag(ordered, 1, 0);
        writeVarint(ordered, seq & 0xFFFFFFFFL);
        writeBytesField(ordered, 2, audioBytes);
        writeTag(ordered, 3, 0);
        writeVarint(ordered, timestamp);
        writeTag(ordered, 6, 0);
        writeVarint(ordered, sessionId);

        byte[] orderedBytes = ordered.toByteArray();
        ByteArrayOutputStream wrapper = new ByteArrayOutputStream(orderedBytes.length + 8);
        writeBytesField(wrapper, 1, orderedBytes);
        return wrapper.toByteArray();
    }

    private void sendFrame(byte[] payload) throws IOException {
        OutputStream out;
        synchronized (stateLock) {
            out = output;
            if (!connected || out == null) throw new IOException("语音通道未连接");
        }
        try {
            synchronized (writeLock) {
                writeRawFrame(out, payload);
            }
            lastSentAt = System.currentTimeMillis();
        } catch (IOException e) {
            closeSocket("语音通道写入失败");
            throw e;
        }
    }

    private static void writeRawFrame(OutputStream out, byte[] payload) throws IOException {
        byte[] header = new byte[8];
        header[0] = (byte) ((PACKET_MAGIC >>> 24) & 0xFF);
        header[1] = (byte) ((PACKET_MAGIC >>> 16) & 0xFF);
        header[2] = (byte) ((PACKET_MAGIC >>> 8) & 0xFF);
        header[3] = (byte) (PACKET_MAGIC & 0xFF);
        int len = payload.length;
        header[4] = (byte) ((len >>> 24) & 0xFF);
        header[5] = (byte) ((len >>> 16) & 0xFF);
        header[6] = (byte) ((len >>> 8) & 0xFF);
        header[7] = (byte) (len & 0xFF);
        out.write(header);
        out.write(payload);
        out.flush();
    }

    private void closeSocket(String reason) {
        synchronized (stateLock) {
            closeSocketLocked(reason);
        }
        notifyListener();
    }

    private void closeSocketLocked(String reason) {
        connected = false;
        if (reason != null && !reason.isEmpty()) lastError = reason;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) {}
            socket = null;
        }
        output = null;
    }

    private void notifyListener() {
        if (listener != null) listener.onAudioConnectionChanged();
    }

    private static void readFully(InputStream in, byte[] dst) throws IOException {
        int offset = 0;
        while (offset < dst.length) {
            int read = in.read(dst, offset, dst.length - offset);
            if (read < 0) throw new EOFException();
            offset += read;
        }
    }

    private static void writeTag(ByteArrayOutputStream out, int field, int wireType) {
        writeVarint(out, ((long) field << 3) | wireType);
    }

    private static void writeBytesField(ByteArrayOutputStream out, int field, byte[] value) {
        writeTag(out, field, 2);
        writeVarint(out, value.length);
        out.write(value, 0, value.length);
    }

    private static void writeStringField(ByteArrayOutputStream out, int field, String value) {
        writeBytesField(out, field, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeVarint(ByteArrayOutputStream out, long value) {
        long v = value;
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7FL) | 0x80L));
            v >>>= 7;
        }
        out.write((int) v);
    }

    private static long readVarint(byte[] data, int pos) {
        long result = 0;
        int shift = 0;
        while (pos < data.length && shift < 64) {
            byte b = data[pos++];
            result |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static int nextVarintPos(byte[] data, int pos) {
        while (pos < data.length) {
            if ((data[pos++] & 0x80) == 0) break;
        }
        return pos;
    }
}
