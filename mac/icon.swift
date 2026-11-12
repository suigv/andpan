import AppKit
import Foundation

func renderIcon(size: Int, roundCorner: Bool) -> NSBitmapImageRep {
    let s = CGFloat(size)
    let rep = NSBitmapImageRep(
        bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
        bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
        colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
    )!
    rep.size = NSSize(width: s, height: s)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    let ctx = NSGraphicsContext.current!.cgContext

    let rect = CGRect(x: 0, y: 0, width: s, height: s)
    let pad = roundCorner ? s * 0.08 : 0
    let box = rect.insetBy(dx: pad, dy: pad)
    let radius = roundCorner ? box.width * 0.225 : 0
    let path = CGPath(roundedRect: box, cornerWidth: radius, cornerHeight: radius, transform: nil)
    ctx.addPath(path)
    ctx.clip()

    let space = CGColorSpaceCreateDeviceRGB()
    let bgColors = [
        CGColor(colorSpace: space, components: [0.06, 0.14, 0.17, 1.0])!,
        CGColor(colorSpace: space, components: [0.035, 0.065, 0.095, 1.0])!
    ] as CFArray
    let bgGrad = CGGradient(colorsSpace: space, colors: bgColors, locations: [0.0, 1.0])!
    ctx.drawLinearGradient(bgGrad, start: CGPoint(x: 0, y: s), end: CGPoint(x: s, y: 0), options: [])

    let glowColors = [
        CGColor(colorSpace: space, components: [0.35, 0.90, 0.72, 0.30])!,
        CGColor(colorSpace: space, components: [0.20, 0.65, 0.55, 0.0])!
    ] as CFArray
    let glow = CGGradient(colorsSpace: space, colors: glowColors, locations: [0.0, 1.0])!
    ctx.drawRadialGradient(glow,
        startCenter: CGPoint(x: s * 0.50, y: s * 0.54), startRadius: 0,
        endCenter: CGPoint(x: s * 0.50, y: s * 0.54), endRadius: s * 0.42, options: [])

    ctx.saveGState()
    ctx.addPath(path)
    ctx.setStrokeColor(CGColor(colorSpace: space, components: [0.60, 0.93, 0.78, 0.26])!)
    ctx.setLineWidth(max(1.5, s * 0.015))
    ctx.strokePath()
    ctx.restoreGState()

    ctx.saveGState()
    let ringRect = CGRect(x: s * 0.17, y: s * 0.17, width: s * 0.66, height: s * 0.66)
    ctx.setStrokeColor(CGColor(colorSpace: space, components: [0.60, 0.93, 0.78, 0.18])!)
    ctx.setLineWidth(max(1.2, s * 0.014))
    ctx.strokeEllipse(in: ringRect)
    ctx.restoreGState()

    let barColor = CGColor(colorSpace: space, components: [0.60, 0.93, 0.78, 0.68])!
    ctx.setFillColor(barColor)
    let barW = s * 0.032
    let bars: [(CGFloat, CGFloat)] = [
        (0.245, 0.12), (0.302, 0.20),
        (0.666, 0.20), (0.723, 0.12)
    ]
    for (bx, bh) in bars {
        let by = s * (0.515 - bh / 2)
        let br = CGRect(x: s * bx, y: by, width: barW, height: s * bh)
        ctx.addPath(CGPath(roundedRect: br, cornerWidth: barW / 2, cornerHeight: barW / 2, transform: nil))
        ctx.fillPath()
    }

    let mint = CGColor(colorSpace: space, components: [0.60, 0.93, 0.78, 1.0])!
    let mintLight = CGColor(colorSpace: space, components: [0.85, 0.99, 0.93, 1.0])!
    let capW = s * 0.155
    let capH = s * 0.255
    let capRect = CGRect(x: (s - capW) / 2, y: s * 0.455, width: capW, height: capH)
    let capPath = CGPath(roundedRect: capRect, cornerWidth: capW / 2, cornerHeight: capW / 2, transform: nil)
    ctx.saveGState()
    ctx.addPath(capPath)
    ctx.clip()
    let capGrad = CGGradient(colorsSpace: space, colors: [mintLight, mint] as CFArray, locations: [0.0, 1.0])!
    ctx.drawLinearGradient(capGrad, start: CGPoint(x: 0, y: capRect.maxY), end: CGPoint(x: 0, y: capRect.minY), options: [])
    ctx.restoreGState()

    ctx.setStrokeColor(mint)
    ctx.setLineCap(.round)
    ctx.setLineWidth(max(1.8, s * 0.036))
    ctx.addArc(center: CGPoint(x: s * 0.50, y: s * 0.52), radius: s * 0.135,
               startAngle: .pi, endAngle: 0, clockwise: false)
    ctx.strokePath()
    ctx.move(to: CGPoint(x: s * 0.50, y: s * 0.385))
    ctx.addLine(to: CGPoint(x: s * 0.50, y: s * 0.31))
    ctx.move(to: CGPoint(x: s * 0.415, y: s * 0.31))
    ctx.addLine(to: CGPoint(x: s * 0.585, y: s * 0.31))
    ctx.strokePath()

    NSGraphicsContext.restoreGraphicsState()
    return rep
}

let args = CommandLine.arguments
guard args.count >= 3 else { exit(1) }
if args[1] == "--icns", args.count == 3 {
    let outIcns = args[2]
    let tmp = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("AndPanIcon-\(UUID().uuidString).iconset")
    try FileManager.default.createDirectory(at: tmp, withIntermediateDirectories: true)
    defer { try? FileManager.default.removeItem(at: tmp) }
    let specs: [(String, Int)] = [
        ("icon_16x16.png", 16), ("icon_16x16@2x.png", 32),
        ("icon_32x32.png", 32), ("icon_32x32@2x.png", 64),
        ("icon_128x128.png", 128), ("icon_128x128@2x.png", 256),
        ("icon_256x256.png", 256), ("icon_256x256@2x.png", 512),
        ("icon_512x512.png", 512), ("icon_512x512@2x.png", 1024),
    ]
    for (name, px) in specs {
        let data = renderIcon(size: px, roundCorner: true).representation(using: .png, properties: [:])!
        try data.write(to: tmp.appendingPathComponent(name))
    }
    let task = Process()
    task.executableURL = URL(fileURLWithPath: "/usr/bin/iconutil")
    task.arguments = ["-c", "icns", tmp.path, "-o", outIcns]
    try task.run()
    task.waitUntilExit()
    exit(task.terminationStatus)
} else if args[1] == "--png", args.count == 4, let px = Int(args[2]) {
    let data = renderIcon(size: px, roundCorner: true).representation(using: .png, properties: [:])!
    try data.write(to: URL(fileURLWithPath: args[3]))
} else {
    exit(1)
}
