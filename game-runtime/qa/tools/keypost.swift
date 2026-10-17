// keypost <pid> <rshift|lshift>: dev-only QA helper. Brings the game (pid) to the front, then posts one real press and
// release of Right or Left Shift through the HID event tap (as a keyboard would), only while that window is frontmost.
// Exit 0 posted, 2 the window never came to the front, 1 usage.
import AppKit
import CoreGraphics

let args = CommandLine.arguments
guard args.count == 3, let pid = pid_t(args[1]), args[2] == "rshift" || args[2] == "lshift" else {
    FileHandle.standardError.write("usage: keypost <pid> <rshift|lshift>\n".data(using: .utf8)!)
    exit(1)
}
let right = args[2] == "rshift"
let keyCode: CGKeyCode = right ? 0x3C : 0x38
// Shift plus the device bit for the side (NX_DEVICERSHIFTKEYMASK 0x04 / NX_DEVICELSHIFTKEYMASK 0x02).
let downFlags = CGEventFlags(rawValue: CGEventFlags.maskShift.rawValue | (right ? 0x04 : 0x02))

guard let app = NSRunningApplication(processIdentifier: pid) else {
    FileHandle.standardError.write("no process \(pid)\n".data(using: .utf8)!)
    exit(2)
}
var front = false
for _ in 0..<40 {
    app.activate(options: [.activateAllWindows])
    Thread.sleep(forTimeInterval: 0.15)
    if NSWorkspace.shared.frontmostApplication?.processIdentifier == pid {
        front = true
        break
    }
}
// macOS may refuse activation from a background tool: click the window's title bar instead (focus only; a title-bar
// click is not passed to the game).
if !front, let windows = CGWindowListCopyWindowInfo([.optionOnScreenOnly, .excludeDesktopElements], kCGNullWindowID) as? [[String: Any]] {
    for w in windows {
        guard (w[kCGWindowOwnerPID as String] as? pid_t) == pid, (w[kCGWindowLayer as String] as? Int) == 0,
              let b = w[kCGWindowBounds as String] as? [String: CGFloat], let x = b["X"], let y = b["Y"], let width = b["Width"] else { continue }
        let point = CGPoint(x: x + width / 2, y: y + 12)
        for type in [CGEventType.leftMouseDown, .leftMouseUp] {
            CGEvent(mouseEventSource: nil, mouseType: type, mouseCursorPosition: point, mouseButton: .left)?.post(tap: .cghidEventTap)
            Thread.sleep(forTimeInterval: 0.05)
        }
        for _ in 0..<20 {
            Thread.sleep(forTimeInterval: 0.1)
            if NSWorkspace.shared.frontmostApplication?.processIdentifier == pid {
                front = true
                break
            }
        }
        break
    }
}
guard front else {
    print("not frontmost (front is \(NSWorkspace.shared.frontmostApplication?.processIdentifier ?? -1))")
    exit(2)
}
Thread.sleep(forTimeInterval: 0.25)
let source = CGEventSource(stateID: .hidSystemState)
func post(_ down: Bool) {
    guard let e = CGEvent(keyboardEventSource: source, virtualKey: keyCode, keyDown: down) else { return }
    e.type = .flagsChanged
    e.flags = down ? downFlags : []
    e.post(tap: .cghidEventTap)
}
post(true)
Thread.sleep(forTimeInterval: 0.08)
post(false)
print("posted \(args[2]) to \(pid)")
exit(0)
