import AVFoundation
import Foundation

let permission = AVCaptureDevice.authorizationStatus(for: .audio)
if permission == .denied || permission == .restricted {
    fputs("没有麦克风权限\n", stderr)
    exit(1)
}
if permission == .notDetermined {
    let semaphore = DispatchSemaphore(value: 0)
    AVCaptureDevice.requestAccess(for: .audio) { _ in semaphore.signal() }
    if semaphore.wait(timeout: .now() + 15) == .timedOut ||
       AVCaptureDevice.authorizationStatus(for: .audio) != .authorized {
        fputs("等待麦克风权限\n", stderr)
        exit(1)
    }
}

let engine = AVAudioEngine()
let input = engine.inputNode
let format = input.inputFormat(forBus: 0)
guard format.channelCount > 0, format.sampleRate > 0 else {
    fputs("无法读取输入设备\n", stderr)
    exit(1)
}
var lastOutput = Date.distantPast
input.installTap(onBus: 0, bufferSize: 2048, format: format) { buffer, _ in
    let now = Date()
    guard now.timeIntervalSince(lastOutput) >= 0.10,
          let channels = buffer.floatChannelData, buffer.frameLength > 0 else { return }
    lastOutput = now
    var total: Double = 0
    for channel in 0..<Int(buffer.format.channelCount) {
        let samples = channels[channel]
        for frame in 0..<Int(buffer.frameLength) {
            let sample = Double(samples[frame])
            total += sample * sample
        }
    }
    let count = Double(buffer.frameLength) * Double(buffer.format.channelCount)
    let db = 20 * log10(max(sqrt(total / count), 0.000001))
    let level = max(0, min(1, (db + 60) / 60))
    print(level)
    fflush(stdout)
}
do {
    try engine.start()
    RunLoop.main.run()
} catch {
    fputs("无法读取 Mac 输入音量\n", stderr)
    exit(1)
}
