// Adapted from Vibe Coding Panel; see LICENSE-vibe-panel.txt.
import AppKit
import ApplicationServices

func attr(_ e: AXUIElement, _ key: String) -> CFTypeRef? {
    var value: CFTypeRef?
    return AXUIElementCopyAttributeValue(e, key as CFString, &value) == .success ? value : nil
}
func label(_ e: AXUIElement) -> String { attr(e, kAXDescriptionAttribute) as? String ?? "" }
var mutationStarted = false
func fail(_ reason: String, recoverable: Bool = false) -> Never {
    let data = try! JSONSerialization.data(withJSONObject: ["error": reason, "code": recoverable && !mutationStarted ? "target_unavailable" : "binding_invalid"])
    print(String(data: data, encoding: .utf8)!); exit(1)
}
func tree(_ root: AXUIElement) -> [AXUIElement] {
    var result: [AXUIElement] = []
    var visited: [CFHashCode: [AXUIElement]] = [:]
    func visit(_ e: AXUIElement, _ depth: Int) {
        let hash = CFHash(e)
        if (visited[hash] ?? []).contains(where: { CFEqual($0, e) }) { return }
        visited[hash, default: []].append(e)
        guard depth < 128, result.count < 12000 else { fail("无法完整检查当前界面，已停止控制") }
        result.append(e)
        for child in attr(e, kAXChildrenAttribute) as? [AXUIElement] ?? [] { visit(child, depth + 1) }
    }
    visit(root, 0); return result
}
guard let request = try? JSONSerialization.jsonObject(with: FileHandle.standardInput.readDataToEndOfFile()) as? [String: Any],
      let action = request["action"] as? String, ["inspect", "write", "send"].contains(action),
      let requestedTitle = request["title"] as? String, (action == "inspect" || !requestedTitle.isEmpty) else { fail("控制请求无效") }
if let session = CGSessionCopyCurrentDictionary() as? [String: Any],
   (session["CGSSessionScreenIsLocked"] as? Bool) == true {
    fail("电脑已锁屏，请先解锁并切回目标会话，再在手机重试；已有文字已保留", recoverable: true)
}
guard AXIsProcessTrusted() else { fail("请在电脑上授予 Connector 辅助功能权限") }
let apps = NSRunningApplication.runningApplications(withBundleIdentifier: "com.openai.codex")
guard apps.count == 1 else { fail("请打开一个 Codex App 实例") }
let app = apps[0], root = AXUIElementCreateApplication(app.processIdentifier)
AXUIElementSetMessagingTimeout(root, 1)
guard (attr(root, kAXWindowsAttribute) as? [AXUIElement])?.count == 1 else { fail("原型只支持一个 Codex 窗口") }
var title = requestedTitle
if title.isEmpty {
    let selected = tree(root).filter {
        let classes = (attr($0, "AXDOMClassList") as? [String]) ?? []
        return (attr($0, kAXRoleAttribute) as? String) == kAXButtonRole && classes.contains("sidebar-item") && classes.contains("bg-primary-ghost-hover")
    }
    guard selected.count == 1, !label(selected[0]).isEmpty else {
        fail("请在 Codex 选中一个会话并点击输入框，再点绑定", recoverable: true)
    }
    title = label(selected[0])
}
func selectedThread(_ nodes: [AXUIElement]) -> AXUIElement {
    let targets = nodes.filter {
        let classes = (attr($0, "AXDOMClassList") as? [String]) ?? []
        return (attr($0, kAXRoleAttribute) as? String) == kAXButtonRole
            && label($0) == title && classes.contains("sidebar-item")
            && classes.contains("bg-primary-ghost-hover")
    }
    guard targets.count == 1 else {
        fail("请在电脑 Codex 切回「\(title)」并点击输入框，再在手机重试；已有文字已保留，无需重新绑定", recoverable: true)
    }
    return targets[0]
}
func composer() -> AXUIElement {
    let nodes = tree(root)
    _ = selectedThread(nodes)
    let fields = nodes.filter { (attr($0, kAXRoleAttribute) as? String) == kAXTextAreaRole }
    guard fields.count == 1 else { fail("无法唯一识别 Codex 输入框") }
    return fields[0]
}
func text(_ field: AXUIElement) -> String {
    guard let value = attr(field, kAXValueAttribute) as? String else { fail("输入框已失效，请重新绑定") }
    let placeholder = label(field)
    let showingPlaceholder = tree(field).contains {
        ((attr($0, "AXDOMClassList") as? [String]) ?? []).contains("placeholder")
    }
    return showingPlaceholder && !placeholder.isEmpty &&
        value.trimmingCharacters(in: .whitespacesAndNewlines) == placeholder ? "" : value
}
func nodeID(_ element: AXUIElement) -> String {
    let raw = attr(element, "ChromeAXNodeId")
    let node = (raw as? NSNumber)?.stringValue ?? (raw as? String)
    guard let node, !node.isEmpty, node.allSatisfy({ $0.isNumber }) else { fail("当前 Codex 版本缺少控件标识") }
    return node
}
func fingerprint() -> String {
    // Composer nodes can be replaced during normal UI rendering. Bind to the
    // unique selected sidebar conversation instead, reacquiring the composer
    // and comparing its actual text before each mutation.
    return "thread-v2:\(app.processIdentifier):\(nodeID(selectedThread(tree(root))))"
}
func conversation(_ nodes: [AXUIElement]) -> (reply: String, activity: String, state: String) {
    let container = nodes.first {
        ((attr($0, "AXDOMClassList") as? [String]) ?? []).contains("thread-scroll-container")
    }
    var reply = ""
    var activity = ""
    if let container {
        func children(_ node: AXUIElement) -> [AXUIElement] {
            attr(node, kAXChildrenAttribute) as? [AXUIElement] ?? []
        }
        func heading(_ node: AXUIElement) -> String {
            guard (attr(node, kAXRoleAttribute) as? String) == kAXHeadingRole else { return "" }
            return tree(node).compactMap { attr($0, kAXValueAttribute) as? String }.joined()
        }
        func body(_ node: AXUIElement) -> String {
            let role = attr(node, kAXRoleAttribute) as? String ?? ""
            let classes = (attr(node, "AXDOMClassList") as? [String]) ?? []
            if classes.contains("sr-only") ||
                [kAXButtonRole, kAXPopUpButtonRole, kAXCheckBoxRole].contains(role) { return "" }
            if role == kAXStaticTextRole || role == "AXListMarker" {
                return attr(node, kAXValueAttribute) as? String ?? ""
            }
            let prefix = classes.contains(where: { $0.hasPrefix("ListItem-") }) ? "\n" : ""
            return prefix + children(node).map(body).joined()
        }
        let assistantHeadings = ["ChatGPT 说：", "Codex 说：", "ChatGPT said:", "Codex said:"]
        let userHeadings = ["你说：", "You said:"]
        for parent in tree(container) {
            let siblings = children(parent)
            if !siblings.contains(where: { assistantHeadings.contains(heading($0)) }) { continue }
            var collecting = false
            for node in siblings {
                let title = heading(node)
                if assistantHeadings.contains(title) {
                    reply = ""
                    collecting = true
                    continue
                }
                if userHeadings.contains(title) { collecting = false; continue }
                guard collecting else { continue }
                let role = attr(node, kAXRoleAttribute) as? String ?? ""
                if role == kAXButtonRole ||
                    tree(node).contains(where: {
                        ((attr($0, "AXDOMClassList") as? [String]) ?? []).contains("group/activity-header")
                    }) {
                    collecting = false
                } else if role == kAXGroupRole || role == kAXListRole || role == kAXHeadingRole {
                    let block = body(node).trimmingCharacters(in: .whitespacesAndNewlines)
                    if !block.isEmpty { reply += (reply.isEmpty ? "" : "\n") + block }
                }
            }
        }
        for node in tree(container) {
            let classes = (attr(node, "AXDOMClassList") as? [String]) ?? []
            if classes.contains(where: { $0.hasPrefix("cadencedShimmerActive-") }) {
                activity = tree(node).compactMap {
                    (attr($0, kAXRoleAttribute) as? String) == kAXStaticTextRole
                        ? attr($0, kAXValueAttribute) as? String : nil
                }.joined()
            }
        }
    }
    let composerButtons = nodes.filter {
        (attr($0, kAXRoleAttribute) as? String) == kAXButtonRole &&
        ((attr($0, "AXDOMClassList") as? [String]) ?? []).contains("bg-composer-primary")
    }
    let state = composerButtons.contains { ["停止", "Stop"].contains(label($0)) } ? "running" :
        composerButtons.contains { ["发送", "Send"].contains(label($0)) } ? "idle" : "unknown"
    // shortcut: accessibility only exposes messages mounted in the current
    // Codex view; use a session API if full history or background access is needed.
    let cleaned = reply.trimmingCharacters(in: .whitespacesAndNewlines)
    let displayed = cleaned.count > 3500 ? String(cleaned.prefix(3500)) + "\n…（内容已截取）" : cleaned
    return (displayed, String(activity.prefix(200)), state)
}
let field = composer(), identity = fingerprint()
if action != "inspect" {
    guard request["fingerprint"] as? String == identity else { fail("会话绑定已变化，请在电脑重新绑定") }
    guard request["expectedText"] as? String == text(field) else { fail("电脑草稿内容已变化，已停止写入以保留你的编辑") }
}
var response: [String: Any] = ["title": title, "fingerprint": identity, "composerNodeId": nodeID(field), "text": text(field), "foreground": NSWorkspace.shared.frontmostApplication?.processIdentifier == app.processIdentifier]
if action == "inspect" {
    let visible = conversation(tree(root))
    guard fingerprint() == identity else { fail("会话绑定已变化，请重新绑定") }
    response["reply"] = visible.reply
    response["activity"] = visible.activity
    response["state"] = visible.state
}
if action == "write" {
    guard let value = request["text"] as? String, value.utf8.count <= 16000 else { fail("草稿过长") }
    let currentField = composer()
    guard fingerprint() == identity, request["expectedText"] as? String == text(currentField) else { fail("目标或草稿已变化") }
    mutationStarted = true
    guard AXUIElementSetAttributeValue(currentField, kAXValueAttribute as CFString, value as CFString) == .success else { fail("写入失败") }
    Thread.sleep(forTimeInterval: 0.15)
    guard fingerprint() == identity, text(composer()) == value else { fail("草稿读回失败，请检查电脑") }
    response["text"] = value
} else if action == "send" {
    guard !text(field).trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
          (attr(field, kAXFocusedAttribute) as? Bool) == true else { fail("请在电脑点击目标会话输入框，再在手机重试，无需重新绑定", recoverable: true) }
    let sends = tree(root).filter { (attr($0, kAXRoleAttribute) as? String) == kAXButtonRole && ["Send", "发送"].contains(label($0)) && (attr($0, kAXEnabledAttribute) as? Bool) == true }
    let currentField = composer()
    guard fingerprint() == identity, request["expectedText"] as? String == text(currentField) else { fail("目标或草稿已变化") }
    guard sends.count == 1, (attr(currentField, kAXFocusedAttribute) as? Bool) == true else {
        fail("Codex 暂时不可发送，请等当前回答结束并确认输入框已选中，再重试；无需重新绑定", recoverable: true)
    }
    let beforePID = NSWorkspace.shared.frontmostApplication?.processIdentifier
    let beforeMouse = CGEvent(source: nil)?.location
    guard let down = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: true),
          let up = CGEvent(keyboardEventSource: nil, virtualKey: 36, keyDown: false) else { fail("无法创建发送事件") }
    mutationStarted = true
    down.postToPid(app.processIdentifier); up.postToPid(app.processIdentifier)
    // Only retain the binding when the same conversation has an empty composer.
    // Never infer successful submission from CGEvent dispatch alone.
    var cleared = false
    for _ in 0..<20 {
        Thread.sleep(forTimeInterval: 0.15)
        let nodes = tree(root)
        let targets = nodes.filter {
            let classes = (attr($0, "AXDOMClassList") as? [String]) ?? []
            return (attr($0, kAXRoleAttribute) as? String) == kAXButtonRole
                && label($0) == title && classes.contains("sidebar-item")
                && classes.contains("bg-primary-ghost-hover")
        }
        guard targets.count == 1,
              "thread-v2:\(app.processIdentifier):\(nodeID(targets[0]))" == identity else { break }
        // The old and new composer can briefly coexist, or both be absent,
        // during submission. Wait for one empty composer instead of treating
        // that intermediate render as a failed Send.
        let fields = nodes.filter { (attr($0, kAXRoleAttribute) as? String) == kAXTextAreaRole }
        if fields.count == 1, text(fields[0]).isEmpty { cleared = true; break }
    }
    response = ["dispatched": true, "composerCleared": cleared, "foregroundUnchanged": beforePID == NSWorkspace.shared.frontmostApplication?.processIdentifier,
                "cursorUnchanged": beforeMouse == CGEvent(source: nil)?.location]
}
print(String(data: try JSONSerialization.data(withJSONObject: response), encoding: .utf8)!)
