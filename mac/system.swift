import AppKit
import ApplicationServices
import CoreAudio
import Foundation

func reply(_ object: [String: Any], failure: Bool = false) -> Never {
    let data = try! JSONSerialization.data(withJSONObject: object)
    print(String(data: data, encoding: .utf8)!)
    exit(failure ? 1 : 0)
}

guard let request = try? JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as? [String: Any],
      let action = request["action"] as? String else {
    reply(["error": "电脑操作无效"], failure: true)
}

if action == "status" {
    var inputName = ""
    var address = AudioObjectPropertyAddress(
        mSelector: kAudioHardwarePropertyDefaultInputDevice,
        mScope: kAudioObjectPropertyScopeGlobal,
        mElement: kAudioObjectPropertyElementMain
    )
    var device = AudioObjectID()
    var size = UInt32(MemoryLayout<AudioObjectID>.size)
    if AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &device) == noErr {
        address.mSelector = kAudioObjectPropertyName
        var value: Unmanaged<CFString>?
        size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        if AudioObjectGetPropertyData(device, &address, 0, nil, &size, &value) == noErr {
            inputName = value?.takeUnretainedValue() as String? ?? ""
        }
    }
    let app = NSWorkspace.shared.frontmostApplication
    reply([
        "input": inputName,
        "accessibility": AXIsProcessTrusted(),
        "target": ["name": app?.localizedName ?? "", "pid": app?.processIdentifier ?? 0],
    ])
}

guard action == "send", let pid = request["pid"] as? Int32, pid > 0 else {
    reply(["error": "发送目标无效"], failure: true)
}
if let session = CGSessionCopyCurrentDictionary() as? [String: Any],
   (session["CGSSessionScreenIsLocked"] as? Bool) == true {
    reply(["error": "Mac 已锁屏，请先解锁"], failure: true)
}
guard NSWorkspace.shared.frontmostApplication?.processIdentifier == pid else {
    reply(["error": "当前应用已切换，请确认后再发送"], failure: true)
}
guard AXIsProcessTrusted() else {
    reply(["error": "请在 Mac 上允许 AndPan 使用辅助功能"], failure: true)
}
guard let down = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: true),
      let up = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: false) else {
    reply(["error": "无法创建回车事件"], failure: true)
}
// Post to the checked process so an app switch cannot redirect this keystroke.
down.postToPid(pid)
up.postToPid(pid)
reply(["dispatched": true])
