import CoreAudio
import Foundation

func fail(_ message: String) -> Never {
    let data = try! JSONSerialization.data(withJSONObject: ["error": message])
    print(String(data: data, encoding: .utf8)!)
    exit(1)
}

let input = FileHandle.standardInput.readDataToEndOfFile()
guard let request = try? JSONSerialization.jsonObject(with: input) as? [String: Any],
      let action = request["action"] as? String, ["get", "set"].contains(action) else {
    fail("麦克风请求无效")
}

var defaultAddress = AudioObjectPropertyAddress(
    mSelector: kAudioHardwarePropertyDefaultInputDevice,
    mScope: kAudioObjectPropertyScopeGlobal,
    mElement: kAudioObjectPropertyElementMain
)
var device = AudioObjectID()
var size = UInt32(MemoryLayout<AudioObjectID>.size)
guard AudioObjectGetPropertyData(AudioObjectID(kAudioObjectSystemObject), &defaultAddress, 0, nil, &size, &device) == noErr else {
    fail("无法读取 Mac 的输入设备")
}

var uidAddress = AudioObjectPropertyAddress(
    mSelector: kAudioDevicePropertyDeviceUID,
    mScope: kAudioObjectPropertyScopeGlobal,
    mElement: kAudioObjectPropertyElementMain
)
var uid: Unmanaged<CFString>?
size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
guard AudioObjectGetPropertyData(device, &uidAddress, 0, nil, &size, &uid) == noErr,
      uid?.takeUnretainedValue() as String? == "BlackHole2ch_UID" else {
    fail("请先将 Mac 的默认输入设备设为 BlackHole 2ch")
}

var muteAddress = AudioObjectPropertyAddress(
    mSelector: kAudioDevicePropertyMute,
    mScope: kAudioDevicePropertyScopeInput,
    mElement: kAudioObjectPropertyElementMain
)
guard AudioObjectHasProperty(device, &muteAddress) else {
    fail("当前 BlackHole 不支持输入静音")
}

if action == "set" {
    guard let muted = request["muted"] as? Bool else { fail("缺少静音状态") }
    var value: UInt32 = muted ? 1 : 0
    let setSize = UInt32(MemoryLayout<UInt32>.size)
    guard AudioObjectSetPropertyData(device, &muteAddress, 0, nil, setSize, &value) == noErr else {
        fail("无法更改 Mac 麦克风静音状态")
    }
}

var value: UInt32 = 0
size = UInt32(MemoryLayout<UInt32>.size)
guard AudioObjectGetPropertyData(device, &muteAddress, 0, nil, &size, &value) == noErr else {
    fail("无法读取 Mac 麦克风静音状态")
}
if action == "set", let expected = request["muted"] as? Bool, (value != 0) != expected {
    fail("Mac 麦克风静音状态未改变")
}
let data = try! JSONSerialization.data(withJSONObject: ["muted": value != 0])
print(String(data: data, encoding: .utf8)!)
