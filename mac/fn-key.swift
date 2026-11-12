import ApplicationServices
import Foundation

func fail(_ message: String) -> Never {
    let data = try! JSONSerialization.data(withJSONObject: ["error": message])
    print(String(data: data, encoding: .utf8)!)
    exit(1)
}

guard let request = try? JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as? [String: Any],
      let action = request["action"] as? String, ["down", "up"].contains(action) else {
    fail("Fn 按键请求无效")
}
guard AXIsProcessTrusted() else { fail("请在 Mac 上允许 AndPan 使用辅助功能") }

let down = action == "down"
guard let event = CGEvent(keyboardEventSource: nil, virtualKey: 63, keyDown: down) else {
    fail("无法创建 Fn 按键事件")
}
event.type = .flagsChanged
var flags = CGEventSource.flagsState(.combinedSessionState)
if down { flags.insert(.maskSecondaryFn) }
else { flags.remove(.maskSecondaryFn) }
event.flags = flags
event.post(tap: .cgSessionEventTap)
print("{\"ok\":true}")
