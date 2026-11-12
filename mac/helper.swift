import AppKit
import ApplicationServices
import Foundation

let arguments = CommandLine.arguments
guard arguments.count == 4 else { exit(1) }
let root = arguments[1], node = arguments[2], port = arguments[3]
let runtime = URL(fileURLWithPath: root).appendingPathComponent(".runtime")
let tokenFile = runtime.appendingPathComponent("token")
guard let token = try? String(contentsOf: tokenFile, encoding: .utf8), !token.isEmpty else { exit(1) }
let logFile = runtime.appendingPathComponent("server.log")
FileManager.default.createFile(atPath: logFile.path, contents: nil)
guard let log = try? FileHandle(forWritingTo: logFile) else { exit(1) }
_ = try? log.seekToEnd()

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
let trusted = AXIsProcessTrustedWithOptions(options)
let message = "AndPan 辅助功能权限：\(trusted ? "已授权" : "等待授权")\n"
log.write(Data(message.utf8))

let task = Process()
task.executableURL = URL(fileURLWithPath: node)
task.arguments = [URL(fileURLWithPath: root).appendingPathComponent("mac/server.mjs").path]
var environment = ProcessInfo.processInfo.environment
environment["ANDPAN_TOKEN"] = token
environment["ANDPAN_PORT"] = port
task.environment = environment
task.standardOutput = log
task.standardError = log
do { try task.run() }
catch {
    log.write(Data("无法启动 AndPan 服务\n".utf8))
    exit(1)
}
try? "\(ProcessInfo.processInfo.processIdentifier)".write(
    to: runtime.appendingPathComponent("server.pid"), atomically: true, encoding: .utf8
)
task.terminationHandler = { _ in DispatchQueue.main.async { app.terminate(nil) } }
signal(SIGTERM, SIG_IGN)
let stop = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
stop.setEventHandler {
    if task.isRunning { task.terminate(); task.waitUntilExit() }
    app.terminate(nil)
}
stop.resume()
app.run()
