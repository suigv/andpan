import AppKit
import Foundation

private enum Theme {
    static let bg = NSColor(srgbRed: 0.043, green: 0.067, blue: 0.090, alpha: 1)
    static let card = NSColor(srgbRed: 0.075, green: 0.114, blue: 0.145, alpha: 1)
    static let cardAlt = NSColor(srgbRed: 0.098, green: 0.149, blue: 0.188, alpha: 1)
    static let border = NSColor(srgbRed: 0.137, green: 0.204, blue: 0.239, alpha: 1)
    static let mint = NSColor(srgbRed: 0.604, green: 0.933, blue: 0.780, alpha: 1)
    static let mintBg = NSColor(srgbRed: 0.110, green: 0.255, blue: 0.220, alpha: 1)
    static let mintBorder = NSColor(srgbRed: 0.240, green: 0.520, blue: 0.440, alpha: 1)
    static let amber = NSColor(srgbRed: 0.941, green: 0.776, blue: 0.455, alpha: 1)
    static let coral = NSColor(srgbRed: 0.941, green: 0.443, blue: 0.471, alpha: 1)
    static let text = NSColor(srgbRed: 0.922, green: 0.957, blue: 0.945, alpha: 1)
    static let muted = NSColor(srgbRed: 0.529, green: 0.616, blue: 0.651, alpha: 1)
}

private final class CardBox: NSView {
    var fillColor: NSColor
    var strokeColor: NSColor
    var cornerRadius: CGFloat

    init(fill: NSColor = Theme.card, stroke: NSColor = Theme.border, radius: CGFloat = 14) {
        self.fillColor = fill
        self.strokeColor = stroke
        self.cornerRadius = radius
        super.init(frame: .zero)
        wantsLayer = true
    }

    required init?(coder: NSCoder) { nil }

    override var wantsUpdateLayer: Bool { true }
    override func updateLayer() {
        layer?.backgroundColor = fillColor.cgColor
        layer?.borderColor = strokeColor.cgColor
        layer?.borderWidth = 1
        layer?.cornerRadius = cornerRadius
    }
}

private final class ActionButton: NSButton {
    enum Style { case primary, secondary, subtle, danger }
    var style: Style { didSet { needsDisplay = true; updateColors() } }
    private let horizontalPad: CGFloat
    private let fixedHeight: CGFloat

    init(title: String, style: Style = .secondary, height: CGFloat = 36, horizontalPad: CGFloat = 16, fontSize: CGFloat = 13) {
        self.style = style
        self.horizontalPad = horizontalPad
        self.fixedHeight = height
        super.init(frame: .zero)
        self.title = title
        isBordered = false
        wantsLayer = true
        font = .systemFont(ofSize: fontSize, weight: .semibold)
        focusRingType = .none
        updateColors()
    }

    required init?(coder: NSCoder) { nil }

    override var intrinsicContentSize: NSSize {
        let base = super.intrinsicContentSize
        return NSSize(width: ceil(base.width) + horizontalPad * 2, height: fixedHeight)
    }

    override var isEnabled: Bool {
        didSet { updateColors() }
    }

    override func updateLayer() { updateColors() }

    private func updateColors() {
        let bg: NSColor
        let border: NSColor
        let fg: NSColor
        switch style {
        case .primary:
            bg = Theme.mint
            border = Theme.mint
            fg = Theme.bg
        case .secondary:
            bg = Theme.mintBg
            border = Theme.mintBorder
            fg = Theme.mint
        case .subtle:
            bg = Theme.cardAlt
            border = Theme.border
            fg = Theme.text
        case .danger:
            bg = NSColor(srgbRed: 0.22, green: 0.11, blue: 0.13, alpha: 1)
            border = NSColor(srgbRed: 0.45, green: 0.20, blue: 0.23, alpha: 1)
            fg = Theme.coral
        }
        layer?.backgroundColor = bg.cgColor
        layer?.borderColor = border.cgColor
        layer?.borderWidth = 1
        layer?.cornerRadius = fixedHeight <= 30 ? 9 : 11
        alphaValue = isEnabled ? 1.0 : 0.42
        let attr = NSMutableAttributedString(string: title, attributes: [
            .foregroundColor: fg,
            .font: font ?? .systemFont(ofSize: 13, weight: .semibold)
        ])
        attributedTitle = attr
    }

    override var title: String {
        didSet { updateColors() }
    }
}

private final class StatusRow {
    let view: NSStackView
    let dot = NSTextField(labelWithString: "●")
    let titleLabel: NSTextField
    let detailLabel = NSTextField(labelWithString: "正在检查")
    let actionButton: ActionButton?

    init(title: String, buttonTitle: String? = nil) {
        dot.font = .systemFont(ofSize: 9)
        dot.textColor = Theme.muted
        dot.setContentHuggingPriority(.required, for: .horizontal)

        titleLabel = NSTextField(labelWithString: title)
        titleLabel.font = .systemFont(ofSize: 13, weight: .medium)
        titleLabel.textColor = Theme.muted
        titleLabel.widthAnchor.constraint(equalToConstant: 82).isActive = true

        detailLabel.font = .systemFont(ofSize: 13)
        detailLabel.textColor = Theme.text
        detailLabel.lineBreakMode = .byTruncatingTail
        detailLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        var views: [NSView] = [dot, titleLabel, detailLabel]
        if let buttonTitle {
            let btn = ActionButton(title: buttonTitle, style: .subtle, height: 26, horizontalPad: 10, fontSize: 11)
            btn.isHidden = true
            btn.setContentHuggingPriority(.required, for: .horizontal)
            views.append(btn)
            self.actionButton = btn
        } else {
            self.actionButton = nil
        }

        view = NSStackView(views: views)
        view.orientation = .horizontal
        view.alignment = .centerY
        view.spacing = 10
    }

    func update(color: NSColor, text: String, showAction: Bool = false, actionTitle: String? = nil) {
        dot.textColor = color
        detailLabel.stringValue = text
        if let actionButton {
            actionButton.isHidden = !showAction
            if let actionTitle { actionButton.title = actionTitle }
        }
    }
}

final class Launcher: NSObject, NSApplicationDelegate {
    private let root: String
    private let window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 572, height: 530),
                                  styleMask: [.titled, .closable, .miniaturizable],
                                  backing: .buffered, defer: false)
    private let statusPill = CardBox(fill: Theme.cardAlt, stroke: Theme.border, radius: 13)
    private let service = NSTextField(labelWithString: "●  正在启动")
    private let address = NSTextField(labelWithString: "正在获取地址")
    private let copyAddressButton = ActionButton(title: "复制地址", style: .subtle, height: 28, horizontalPad: 12, fontSize: 12)
    private let phoneRow = StatusRow(title: "手机面板")
    private let audioRow = StatusRow(title: "语音通道")
    private let inputRow = StatusRow(title: "Mac 输入", buttonTitle: "打开设置")
    private let message = NSTextField(wrappingLabelWithString: "")
    private let start = ActionButton(title: "启动服务", style: .primary, height: 36, horizontalPad: 18)
    private let stop = ActionButton(title: "停止服务", style: .danger, height: 36, horizontalPad: 18)
    private let install = ActionButton(title: "安装 / 更新手机端", style: .secondary, height: 36, horizontalPad: 18)

    private var statusItem: NSStatusItem?
    private let menuSummary = NSMenuItem(title: "AndPan：正在启动", action: nil, keyEquivalent: "")
    private var currentIP = ""
    private var copyResetGen = 0
    private var pendingSettingsURL = ""
    private var busy = false
    private var checking = false
    private var timer: Timer?
    private var lastAddressCheck = Date.distantPast

    override init() {
        let file = Bundle.main.url(forResource: "root", withExtension: "txt")
        root = file.flatMap { try? String(contentsOf: $0, encoding: .utf8) }?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        super.init()
    }

    private func label(_ text: String, size: CGFloat, weight: NSFont.Weight = .regular, color: NSColor = Theme.text) -> NSTextField {
        let result = NSTextField(labelWithString: text)
        result.font = NSFont.systemFont(ofSize: size, weight: weight)
        result.textColor = color
        return result
    }

    private func row(_ views: [NSView], spacing: CGFloat = 10) -> NSStackView {
        let result = NSStackView(views: views)
        result.orientation = .horizontal
        result.alignment = .centerY
        result.spacing = spacing
        return result
    }

    private func makeMenuIcon() -> NSImage {
        let img = NSImage(size: NSSize(width: 18, height: 18), flipped: false) { _ in
            NSColor.black.setStroke()
            NSColor.black.setFill()
            let cap = NSBezierPath(roundedRect: NSRect(x: 6.75, y: 7.5, width: 4.5, height: 7.5), xRadius: 2.25, yRadius: 2.25)
            cap.fill()
            let arc = NSBezierPath()
            arc.lineWidth = 1.5
            arc.lineCapStyle = .round
            arc.appendArc(withCenter: NSPoint(x: 9, y: 9.5), radius: 4.6, startAngle: 180, endAngle: 0, clockwise: false)
            arc.move(to: NSPoint(x: 9, y: 4.9))
            arc.line(to: NSPoint(x: 9, y: 2.6))
            arc.move(to: NSPoint(x: 6.2, y: 2.6))
            arc.line(to: NSPoint(x: 11.8, y: 2.6))
            arc.stroke()
            return true
        }
        img.isTemplate = true
        return img
    }

    private func setupMenuBar() {
        let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
        item.button?.image = makeMenuIcon()
        item.button?.toolTip = "AndPan"
        let menu = NSMenu()
        menuSummary.isEnabled = false
        menu.addItem(menuSummary)
        menu.addItem(.separator())
        let showItem = NSMenuItem(title: "显示主面板", action: #selector(showPanel), keyEquivalent: "")
        showItem.target = self
        menu.addItem(showItem)
        let startItem = NSMenuItem(title: "启动服务", action: #selector(startService), keyEquivalent: "")
        startItem.target = self
        menu.addItem(startItem)
        let stopItem = NSMenuItem(title: "停止服务", action: #selector(stopService), keyEquivalent: "")
        stopItem.target = self
        menu.addItem(stopItem)
        let installItem = NSMenuItem(title: "安装 / 更新手机端", action: #selector(installPhone), keyEquivalent: "")
        installItem.target = self
        menu.addItem(installItem)
        menu.addItem(.separator())
        menu.addItem(withTitle: "退出 AndPan", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        item.menu = menu
        statusItem = item
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        let menu = NSMenu()
        let appItem = NSMenuItem()
        menu.addItem(appItem)
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "退出 AndPan", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        NSApp.mainMenu = menu
        setupMenuBar()

        window.title = "AndPan"
        window.appearance = NSAppearance(named: .darkAqua)
        window.titlebarAppearsTransparent = true
        window.titleVisibility = .hidden
        window.backgroundColor = Theme.bg
        window.isMovableByWindowBackground = true
        window.center()
        window.isReleasedWhenClosed = false

        let iconView = NSImageView()
        iconView.image = NSApp.applicationIconImage
        iconView.imageScaling = .scaleProportionallyUpOrDown
        iconView.wantsLayer = true
        iconView.layer?.cornerRadius = 11
        iconView.layer?.masksToBounds = true
        NSLayoutConstraint.activate([
            iconView.widthAnchor.constraint(equalToConstant: 46),
            iconView.heightAnchor.constraint(equalToConstant: 46),
        ])

        let title = label("AndPan", size: 22, weight: .bold)
        let subtitle = label("手机语音直连与桌面控制台", size: 12.5, color: Theme.muted)
        let header = NSStackView(views: [title, subtitle])
        header.orientation = .vertical
        header.alignment = .leading
        header.spacing = 2

        service.font = .systemFont(ofSize: 12.5, weight: .semibold)
        service.textColor = Theme.muted
        service.translatesAutoresizingMaskIntoConstraints = false
        statusPill.addSubview(service)
        NSLayoutConstraint.activate([
            service.leadingAnchor.constraint(equalTo: statusPill.leadingAnchor, constant: 12),
            service.trailingAnchor.constraint(equalTo: statusPill.trailingAnchor, constant: -12),
            service.topAnchor.constraint(equalTo: statusPill.topAnchor, constant: 6),
            service.bottomAnchor.constraint(equalTo: statusPill.bottomAnchor, constant: -6),
        ])

        let spacer = NSView()
        spacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        let headerRow = row([iconView, header, spacer, statusPill], spacing: 14)

        let addressCard = CardBox()
        let addressTitle = label("局域网连接地址", size: 12, weight: .semibold, color: Theme.muted)
        let addressTip = label("同 Wi-Fi 下手机自动发现；也可在手机设置中手填", size: 11.5, color: Theme.muted)
        address.font = .monospacedSystemFont(ofSize: 22, weight: .semibold)
        address.textColor = Theme.mint
        copyAddressButton.target = self
        copyAddressButton.action = #selector(copyAddress)
        copyAddressButton.isEnabled = false
        let ipSpacer = NSView()
        let ipRow = row([address, ipSpacer, copyAddressButton], spacing: 12)
        let addressStack = NSStackView(views: [addressTitle, ipRow, addressTip])
        addressStack.orientation = .vertical
        addressStack.alignment = .leading
        addressStack.spacing = 6
        addressStack.translatesAutoresizingMaskIntoConstraints = false
        addressCard.addSubview(addressStack)
        NSLayoutConstraint.activate([
            addressStack.leadingAnchor.constraint(equalTo: addressCard.leadingAnchor, constant: 16),
            addressStack.trailingAnchor.constraint(equalTo: addressCard.trailingAnchor, constant: -16),
            addressStack.topAnchor.constraint(equalTo: addressCard.topAnchor, constant: 14),
            addressStack.bottomAnchor.constraint(equalTo: addressCard.bottomAnchor, constant: -14),
        ])

        let statusCard = CardBox()
        inputRow.actionButton?.target = self
        inputRow.actionButton?.action = #selector(openSystemSettings)
        let divider1 = CardBox(fill: Theme.border, stroke: .clear, radius: 0)
        divider1.heightAnchor.constraint(equalToConstant: 1).isActive = true
        let divider2 = CardBox(fill: Theme.border, stroke: .clear, radius: 0)
        divider2.heightAnchor.constraint(equalToConstant: 1).isActive = true
        let statusStack = NSStackView(views: [
            label("运行状态", size: 12, weight: .semibold, color: Theme.muted),
            phoneRow.view, divider1, audioRow.view, divider2, inputRow.view
        ])
        statusStack.orientation = .vertical
        statusStack.alignment = .leading
        statusStack.spacing = 10
        statusStack.translatesAutoresizingMaskIntoConstraints = false
        statusCard.addSubview(statusStack)
        NSLayoutConstraint.activate([
            statusStack.leadingAnchor.constraint(equalTo: statusCard.leadingAnchor, constant: 16),
            statusStack.trailingAnchor.constraint(equalTo: statusCard.trailingAnchor, constant: -16),
            statusStack.topAnchor.constraint(equalTo: statusCard.topAnchor, constant: 14),
            statusStack.bottomAnchor.constraint(equalTo: statusCard.bottomAnchor, constant: -14),
            phoneRow.view.widthAnchor.constraint(equalTo: statusStack.widthAnchor),
            audioRow.view.widthAnchor.constraint(equalTo: statusStack.widthAnchor),
            inputRow.view.widthAnchor.constraint(equalTo: statusStack.widthAnchor),
            divider1.widthAnchor.constraint(equalTo: statusStack.widthAnchor),
            divider2.widthAnchor.constraint(equalTo: statusStack.widthAnchor),
        ])

        message.font = .systemFont(ofSize: 12)
        message.textColor = Theme.muted
        message.maximumNumberOfLines = 3

        start.target = self; start.action = #selector(startService)
        stop.target = self; stop.action = #selector(stopService)
        install.target = self; install.action = #selector(installPhone)
        let btnSpacer = NSView()
        let actionsRow = row([start, stop, btnSpacer, install], spacing: 10)

        let help = NSTextField(wrappingLabelWithString:
            "关闭窗口后服务仍在后台和菜单栏运行；首次用 USB 安装后即可拔线。发送回车前请先选中 Mac 目标应用的输入框。")
        help.font = .systemFont(ofSize: 11.5)
        help.textColor = Theme.muted

        let stack = NSStackView()
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 14
        stack.translatesAutoresizingMaskIntoConstraints = false
        stack.addArrangedSubview(headerRow)
        stack.addArrangedSubview(addressCard)
        stack.addArrangedSubview(statusCard)
        stack.addArrangedSubview(actionsRow)
        stack.addArrangedSubview(help)
        stack.addArrangedSubview(message)
        window.contentView?.addSubview(stack)
        if let content = window.contentView {
            NSLayoutConstraint.activate([
                stack.leadingAnchor.constraint(equalTo: content.leadingAnchor, constant: 24),
                stack.trailingAnchor.constraint(equalTo: content.trailingAnchor, constant: -24),
                stack.topAnchor.constraint(equalTo: content.topAnchor, constant: 34),
                headerRow.widthAnchor.constraint(equalTo: stack.widthAnchor),
                addressCard.widthAnchor.constraint(equalTo: stack.widthAnchor),
                statusCard.widthAnchor.constraint(equalTo: stack.widthAnchor),
                actionsRow.widthAnchor.constraint(equalTo: stack.widthAnchor),
            ])
        }
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
        runService("start.command")
        timer = Timer.scheduledTimer(withTimeInterval: 2.5, repeats: true) { [weak self] _ in self?.refresh() }
        refresh()
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        showPanel()
        return true
    }

    @objc private func showPanel() {
        window.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc private func copyAddress() {
        guard !currentIP.isEmpty else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(currentIP, forType: .string)
        copyResetGen += 1
        let gen = copyResetGen
        copyAddressButton.title = "已复制 ✓"
        copyAddressButton.style = .secondary
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.8) { [weak self] in
            guard let self, self.copyResetGen == gen else { return }
            self.copyAddressButton.title = "复制地址"
            self.copyAddressButton.style = .subtle
        }
    }

    @objc private func openSystemSettings() {
        guard !pendingSettingsURL.isEmpty, let url = URL(string: pendingSettingsURL) else { return }
        NSWorkspace.shared.open(url)
    }

    private func shell(_ executable: String, _ arguments: [String]) -> (Int32, String) {
        let process = Process()
        process.executableURL = URL(fileURLWithPath: executable)
        process.arguments = arguments
        let output = Pipe()
        process.standardOutput = output
        process.standardError = output
        do {
            try process.run()
            let data = output.fileHandleForReading.readDataToEndOfFile()
            process.waitUntilExit()
            return (process.terminationStatus, String(data: data, encoding: .utf8) ?? "")
        } catch { return (1, error.localizedDescription) }
    }

    private func runService(_ script: String) {
        guard !busy else { return }
        guard !root.isEmpty else {
            message.stringValue = "找不到 AndPan 安装目录，请重新构建 Mac 应用。"
            return
        }
        busy = true
        start.isEnabled = false
        stop.isEnabled = false
        install.isEnabled = false
        if script == "start.command" {
            service.stringValue = "●  正在启动"
            service.textColor = Theme.amber
        }
        if script == "stop.command" {
            service.stringValue = "●  正在停止"
            service.textColor = Theme.amber
        }
        message.stringValue = script == "install.command" ? "正在安装手机端，请留意手机上的授权提示。" : ""
        message.textColor = Theme.muted
        let scriptPath = URL(fileURLWithPath: root).appendingPathComponent("mac/\(script)").path
        DispatchQueue.global(qos: .userInitiated).async {
            let (code, output) = self.shell("/bin/bash", [scriptPath])
            DispatchQueue.main.async {
                self.busy = false
                self.start.isEnabled = true
                self.stop.isEnabled = true
                self.install.isEnabled = true
                self.message.textColor = code == 0 ? Theme.mint : Theme.coral
                self.message.stringValue = code == 0
                    ? (script == "install.command" ? "手机端已安装并完成连接设置，可以拔掉 USB。"
                       : "操作完成；关闭窗口后服务继续在后台运行。")
                    : output.trimmingCharacters(in: .whitespacesAndNewlines)
                self.refresh()
            }
        }
    }

    @objc private func startService() { runService("start.command") }
    @objc private func stopService() { runService("stop.command") }
    @objc private func installPhone() { runService("install.command") }

    private func request(_ path: String, completion: @escaping ([String: Any]?) -> Void) {
        let tokenPath = URL(fileURLWithPath: root).appendingPathComponent(".runtime/token").path
        guard let token = try? String(contentsOfFile: tokenPath, encoding: .utf8),
              let url = URL(string: "http://127.0.0.1:43125\(path)") else {
            completion(nil); return
        }
        var request = URLRequest(url: url, timeoutInterval: 3)
        request.setValue("Bearer \(token.trimmingCharacters(in: .whitespacesAndNewlines))", forHTTPHeaderField: "Authorization")
        URLSession.shared.dataTask(with: request) { data, response, _ in
            let result = ((response as? HTTPURLResponse)?.statusCode == 200)
                ? data.flatMap { (try? JSONSerialization.jsonObject(with: $0)) as? [String: Any] } : nil
            DispatchQueue.main.async { completion(result) }
        }.resume()
    }

    private func refresh() {
        if Date().timeIntervalSince(lastAddressCheck) > 15 {
            lastAddressCheck = Date()
            DispatchQueue.global(qos: .utility).async {
                let command = "i=$(/sbin/route -n get default 2>/dev/null | /usr/bin/awk '$1==\"interface:\" {print $2; exit}'); /usr/sbin/ipconfig getifaddr \"$i\" 2>/dev/null"
                let (_, result) = self.shell("/bin/bash", ["-c", command])
                let value = result.trimmingCharacters(in: .whitespacesAndNewlines)
                DispatchQueue.main.async {
                    self.currentIP = value
                    self.address.stringValue = value.isEmpty ? "未检测到局域网地址" : value
                    self.address.textColor = value.isEmpty ? Theme.muted : Theme.mint
                    self.copyAddressButton.isEnabled = !value.isEmpty
                }
            }
        }
        guard !checking else { return }
        checking = true
        request("/health") { health in
            self.checking = false
            guard let health else {
                if !self.busy {
                    self.service.stringValue = "●  服务未运行"
                    self.service.textColor = Theme.muted
                    self.statusPill.fillColor = Theme.cardAlt
                    self.statusPill.strokeColor = Theme.border
                    self.statusPill.needsDisplay = true
                }
                self.menuSummary.title = "AndPan：服务未运行"
                self.phoneRow.update(color: Theme.muted, text: "未连接")
                self.audioRow.update(color: Theme.muted, text: "未运行")
                self.inputRow.update(color: Theme.muted, text: "无法确认")
                return
            }
            if !self.busy {
                self.service.stringValue = "●  服务运行中"
                self.service.textColor = Theme.mint
                self.statusPill.fillColor = Theme.mintBg
                self.statusPill.strokeColor = Theme.mintBorder
                self.statusPill.needsDisplay = true
            }
            let phoneConnected = (health["phoneConnected"] as? Bool == true)
            self.phoneRow.update(color: phoneConnected ? Theme.mint : Theme.muted,
                                 text: phoneConnected ? "已连接" : "未连接（打开手机端即可自动连上）")
            self.menuSummary.title = phoneConnected ? "AndPan：运行中 · 手机已连接" : "AndPan：运行中 · 等待手机"
            let mic = (health["micYou"] as? [String: Any])?["state"] as? String
            let micInfo = health["micYou"] as? [String: Any]
            let micService = micInfo?["service"] as? String ?? ""
            let micError = micInfo?["error"] as? String ?? ""
            let micPort = micInfo?["port"] as? Int ?? 8554
            if !micError.isEmpty {
                self.audioRow.update(color: Theme.coral, text: micError)
            } else if mic == "connected" {
                self.audioRow.update(color: Theme.mint, text: "已内置运行 (\(micPort)) · 已连接手机")
            } else if micService == "running" || mic == "disconnected" {
                self.audioRow.update(color: Theme.amber, text: "已内置运行 (\(micPort)) · 等待手机连接")
            } else {
                self.audioRow.update(color: Theme.muted, text: "无法确认")
            }
            let input = health["microphone"] as? [String: Any]
            let system = health["system"] as? [String: Any]
            if system?["accessibility"] as? Bool == false {
                self.pendingSettingsURL = "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
                self.inputRow.update(color: Theme.coral, text: "缺少辅助功能权限，请重新授权 AndPan Mac", showAction: true, actionTitle: "打开权限设置")
            } else if input?["available"] as? Bool == true {
                self.pendingSettingsURL = ""
                self.inputRow.update(color: Theme.mint, text: "BlackHole 2ch 已就绪 · 设备控制已授权", showAction: false)
            } else {
                self.pendingSettingsURL = "x-apple.systempreferences:com.apple.preference.sound?input"
                self.inputRow.update(color: Theme.amber, text: input?["error"] as? String ?? "无法确认", showAction: true, actionTitle: "打开声音设置")
            }
        }
    }
}

let app = NSApplication.shared
let delegate = Launcher()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
